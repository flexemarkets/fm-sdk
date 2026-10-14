/**
 * The first-robot example does what docs/FIRST-ROBOT.md says it does: its one
 * step, FirstRobot.tick, driven by a desk that answers from the test.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { InvalidArgumentError } from "../src/client.ts";
import { FirstRobot } from "../src/first-robot.ts";
import type { Market } from "../src/types.ts";

const A = { id: 11, symbol: "A", priceMinimum: 0, priceMaximum: 10_000, priceTick: 5 } as Market;

function desk(options: { state?: string; canBuy?: boolean; canSell?: boolean; bid?: number; ask?: number } = {}) {
  let next = 100;
  const d = {
    state: options.state ?? "OPEN", bid: options.bid ?? 400, ask: options.ask ?? 600,
    refuse: null as string | null, sent: [] as string[],
    markets: [A],
    session: () => ({ id: 1, original: 1, state: d.state }) as never,
    holding: () => ({ securities: [{ marketId: A.id, canBuy: options.canBuy ?? true, canSell: options.canSell ?? true }] }) as never,
    book: () => ({ bestBuyPrice: () => d.bid, bestSellPrice: () => d.ask }) as never,
    async submitLimit(_m: number, side: string, units: number, price: number) {
      if (d.refuse) throw new InvalidArgumentError(d.refuse);
      d.sent.push(`${side} ${units}@${price}`);
      next++;
      return { id: next, original: next } as never;
    },
    async submitCancel(_m: number, original: number) {
      d.sent.push(`CANCEL ${original}`);
      return {} as never;
    },
  };
  return d;
}

test("it quotes one tick inside the spread on both sides", async () => {
  const d = desk();
  await new FirstRobot(d).tick();
  assert.deepEqual(d.sent, ["BUY 1@405", "SELL 1@595"]);
});

test("it sends nothing unless the session is open", async () => {
  for (const state of ["INIT", "PAUSED", "CLOSED"]) {
    const d = desk({ state });
    await new FirstRobot(d).tick();
    assert.deepEqual(d.sent, [], state);
  }
});

test("it quotes only the sides its holding grants", async () => {
  const buyOnly = desk({ canSell: false });
  await new FirstRobot(buyOnly).tick();
  assert.deepEqual(buyOnly.sent, ["BUY 1@405"]);
  const sellOnly = desk({ canBuy: false });
  await new FirstRobot(sellOnly).tick();
  assert.deepEqual(sellOnly.sent, ["SELL 1@595"]);
});

test("it never crosses the book", async () => {
  const d = desk({ bid: 400, ask: 405 });
  await new FirstRobot(d).tick();
  assert.deepEqual(d.sent, []);
});

test("a moved price replaces the quote and an unmoved one is left alone", async () => {
  const d = desk();
  const robot = new FirstRobot(d);
  await robot.tick();
  await robot.tick();
  d.bid = 420;
  await robot.tick();
  assert.deepEqual(d.sent, ["BUY 1@405", "SELL 1@595", "CANCEL 101", "BUY 1@425"]);
});

test("a refused order is reported and the robot carries on", async () => {
  const d = desk();
  d.refuse = "ORDER_NOT_ALLOWED";
  const robot = new FirstRobot(d);
  await robot.tick();
  assert.deepEqual(robot.refusals, ["ORDER_NOT_ALLOWED", "ORDER_NOT_ALLOWED"]);
  d.refuse = null;
  await robot.tick();
  assert.deepEqual(d.sent, ["BUY 1@405", "SELL 1@595"]);
});

test("stopping cancels what is resting", async () => {
  const d = desk();
  const robot = new FirstRobot(d);
  await robot.tick();
  await robot.cancelAll();
  assert.deepEqual(d.sent.slice(-2), ["CANCEL 101", "CANCEL 102"]);
  assert.equal(robot.resting.size, 0);
});
