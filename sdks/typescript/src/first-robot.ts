#!/usr/bin/env node
/**
 * fm-first-robot -- the smallest robot that trades politely.
 *
 * A worked example for docs/FIRST-ROBOT.md, and the TypeScript twin of
 * sdks/python/first_robot.py. Every few seconds, while the session is OPEN, it
 * quotes one unit one tick inside the spread on each side its holding allows,
 * and replaces a quote when the best price moves. Not a strategy worth
 * copying; the shape a robot should have:
 *
 * - it quotes only while the session is OPEN, so it sends nothing the server
 *   will refuse after the close;
 * - it quotes only the sides its holding grants;
 * - it keeps one order per side, cancelling the old one before placing anew;
 * - a refused order is reported and the robot carries on;
 * - it cancels what it has resting when it stops, and stops when the session
 *   closes or on Ctrl-C.
 *
 *     npx tsx src/first-robot.ts -C ~/.fm/credential -E 1234
 */

import { pathToFileURL } from "node:url";

import { Flexemarkets, InvalidArgumentError } from "./client.js";
import type { Desk } from "./desk.js";
import { getSecurity, priceRound, SESSION_STATE_CLOSED, SESSION_STATE_OPEN, type Market } from "./types.js";

/** How often the robot looks at the book. Not faster: a robot acting every 0.2 s is most of a class's traffic. */
export const INTERVAL_MS = 2000;

/** The prices to quote one unit at, by side: one tick inside the spread, never crossing it. Cents. */
export function quotes(market: Market, bestBuy: number, bestSell: number,
                       canBuy: boolean, canSell: boolean): { BUY?: number; SELL?: number } {
  const tick = market.priceTick || 1;
  const wanted: { BUY?: number; SELL?: number } = {};
  if (canBuy && bestBuy >= 0) {
    const price = priceRound(market, bestBuy + tick);
    if (bestSell < 0 || price < bestSell) wanted.BUY = price;
  }
  if (canSell && bestSell >= 0) {
    const price = priceRound(market, bestSell - tick);
    if (bestBuy < 0 || price > bestBuy) wanted.SELL = price;
  }
  return wanted;
}

type DeskLike = Pick<Desk, "session" | "holding" | "markets" | "book" | "submitLimit" | "submitCancel">;

/** One robot on one desk. {@link tick} is one look at the book. */
export class FirstRobot {
  readonly resting = new Map<string, { original: number; price: number }>();
  readonly refusals: string[] = [];

  constructor(private readonly desk: DeskLike) {}

  async tick(): Promise<void> {
    const session = this.desk.session();
    if (session === null || session.state !== SESSION_STATE_OPEN) return;
    const holding = this.desk.holding();
    for (const market of this.desk.markets) {
      const security = holding !== null ? getSecurity(holding, market.id) : null;
      const book = this.desk.book(market.id);
      if (security === null || book === null) continue;
      const wanted = quotes(market, book.bestBuyPrice(), book.bestSellPrice(), security.canBuy, security.canSell);
      for (const side of ["BUY", "SELL"] as const) {
        await this.requote(market.id, side, wanted[side]);
      }
    }
  }

  private async requote(marketId: number, side: "BUY" | "SELL", price: number | undefined): Promise<void> {
    const key = `${marketId}/${side}`;
    const current = this.resting.get(key);
    if (current !== undefined && current.price === price) return;   // already where we want it
    if (current !== undefined) {
      await this.send(() => this.desk.submitCancel(marketId, current.original));
      this.resting.delete(key);
    }
    if (price !== undefined) {
      const order = await this.send(() => this.desk.submitLimit(marketId, side, 1, price));
      if (order) this.resting.set(key, { original: order.original ?? order.id, price });
    }
  }

  /** One order, reported rather than thrown when it is refused. */
  private async send<T>(call: () => Promise<T>): Promise<T | null> {
    try {
      return await call();
    } catch (refused) {
      if (!(refused instanceof InvalidArgumentError)) throw refused;
      // The server said no -- a closed session, a side not granted, a price off
      // the grid. Say so and carry on with the next tick.
      this.refusals.push(refused.message);
      console.log(`refused: ${refused.message}`);
      return null;
    }
  }

  async cancelAll(): Promise<void> {
    for (const [key, { original }] of this.resting) {
      await this.send(() => this.desk.submitCancel(Number(key.split("/")[0]), original));
    }
    this.resting.clear();
  }
}

function arg(flag: string, long: string): string | undefined {
  const i = process.argv.findIndex((a) => a === flag || a === long);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

async function main(): Promise<void> {
  const fm = await Flexemarkets.connect(arg("-C", "--credential"), arg("-E", "--endpoint"), "fm-first-robot");
  const desk = await fm.desk(fm.endpointMarketplaceId);
  const robot = new FirstRobot(desk);
  let stopping = false;
  const stop = async (why: string) => {
    if (stopping) return;
    stopping = true;
    console.log(why);
    clearInterval(timer);
    await robot.cancelAll();
    desk.close();
    await fm.close();
  };
  const timer = setInterval(() => void robot.tick(), INTERVAL_MS);
  desk.onSessionChange((s) => { if (s.state === SESSION_STATE_CLOSED) void stop("Session closed."); });
  process.on("SIGINT", () => void stop("\nStopped."));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
