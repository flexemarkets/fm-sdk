#!/usr/bin/env tsx
/**
 * fm-ticker — live order book and trade history display.
 *
 * Port of python/ticker.py.
 */

import { Flexemarkets } from "./client.js";
import type { Desk } from "./desk.js";
import type { Session } from "./types.js";
import { SESSION_STATE_CLOSED } from "./types.js";

const TRADE_DISPLAY_COUNT = 5;

function price(value: number): string {
  if (value < 0) return "     -";
  return `$${(value / 100).toFixed(2).padStart(5)}`;
}

function spread(bid: number, ask: number): string {
  if (bid < 0 || ask < 0) return "     -";
  return `$${((ask - bid) / 100).toFixed(2).padStart(5)}`;
}

function tradePrices(prices: number[], count: number): string {
  const recent = prices.length > count ? prices.slice(-count) : prices;
  return [...recent]
    .reverse()
    .map((p) => `$${(p / 100).toFixed(2)}`)
    .join("  ");
}

function display(
  desk: Desk,
  session: Session | null,
  endpointUrl: string = "",
): void {
  const state = session?.state ?? "---";

  const lines: string[] = [];
  lines.push("\x1b[2J\x1b[H"); // clear screen, cursor home
  lines.push(`fm-ticker  ${endpointUrl}`);
  lines.push(state.padStart(59));
  lines.push("");
  lines.push(
    `  ${"Symbol".padStart(6)}  ${"Bid".padStart(6)}  ${"Ask".padStart(6)}  ${"Spread".padStart(6)}   Last trades`,
  );
  lines.push(
    `  ${"------".padStart(6)}  ${"------".padStart(6)}  ${"------".padStart(6)}  ${"------".padStart(6)}   -----------`,
  );

  const sorted = [...desk.books()].sort((a, b) => a.marketId - b.marketId);
  for (const book of sorted) {
    const bid = book.bestBuyPrice();
    const ask = book.bestSellPrice();
    const symbol = book.symbol ?? "?";
    const recent = desk.tape(book.marketId)?.mostRecentPrices() ?? [];
    lines.push(
      `  ${symbol.padStart(6)}  ${price(bid)}  ${price(ask)}  ${spread(bid, ask)}   ${tradePrices(recent, TRADE_DISPLAY_COUNT)}`,
    );
  }

  lines.push("");
  process.stdout.write(lines.join("\n"));
}

async function main(): Promise<void> {
  // Parse CLI args
  const args = process.argv.slice(2);
  let credential: string | null = null;
  let endpoint: string | null = null;

  for (let i = 0; i < args.length; i++) {
    if ((args[i] === "-C" || args[i] === "--credential") && i + 1 < args.length) {
      credential = args[++i];
    } else if ((args[i] === "-E" || args[i] === "--endpoint") && i + 1 < args.length) {
      endpoint = args[++i];
    }
  }

  const fm = await Flexemarkets.connect(credential, endpoint, "fm-ticker");
  try {
    const marketplaceId = fm.endpointMarketplaceId;
    const markets = await fm.markets(marketplaceId);
    markets.sort((a, b) => a.id - b.id);

    // A desk keeps the books and the tape for us: seeded from the REST
    // snapshot, kept current from the same delta stream, and reseeded on a
    // sequence gap. That is the whole reason this example no longer holds
    // aggregators of its own.
    const desk = await fm.desk(marketplaceId);
    const endpointUrl = fm.endpointUrl;

    let session: Session | null = null;
    display(desk, session, endpointUrl);

    desk.onSessionChange((s: Session) => {
      session = s;
      display(desk, session, endpointUrl);
      if (s.state === SESSION_STATE_CLOSED) {
        console.log("Session closed.");
        desk.close();
        fm.close();
        process.exit(0);
      }
    });

    for (const market of markets) {
      desk.onBookChange(market.id, () => display(desk, session, endpointUrl));
    }

    // Keep alive — listen for Ctrl+C
    process.on("SIGINT", () => {
      console.log("\nStopped.");
      fm.close();
      process.exit(0);
    });

    // Keep the process alive
    await new Promise(() => {});
  } catch (err) {
    fm.close();
    throw err;
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
