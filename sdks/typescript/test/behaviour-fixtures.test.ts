/**
 * Every behaviour fixture, driven through this SDK's aggregators.
 *
 * The wire fixtures next door compare *parsed field values*: one payload in,
 * one set of fields out. They say nothing about what Book and Tape do
 * with a sequence of them, which is where the three SDKs have actually been
 * wrong together — a book that double-counts a cancel and a tape that holds its
 * trades backwards both parse every field correctly.
 *
 * So these are inputs and answers rather than payloads and fields: a market, a
 * list of update steps, and what the aggregator must hold at the end. Java,
 * Python and TypeScript each run all of them, so a behaviour cannot be right in
 * one SDK and wrong in another without saying so.
 *
 * See `sdks/fixtures/README.md`.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { parseOrder } from "../src/client.js";
import { Book } from "../src/orderbook.js";
import { Tape, type Trade } from "../src/trades.js";
import type { Market, Order } from "../src/types.js";

const DIR = fileURLToPath(new URL("../../fixtures/behaviour/", import.meta.url));
const FILES = readdirSync(DIR).filter((f) => f.endsWith(".json")).sort();

interface Step { orders: Record<string, unknown>[]; clear?: boolean; adds?: number; note?: string }
interface Fixture {
  type: string;
  market: { id: number; symbol: string };
  deliveredIds: number[];
  steps: Step[];
  expect: Record<string, unknown>;
}

const AGGREGATOR_TYPES = new Set(["Book", "Tape"]);

function market(doc: Fixture): Market {
  return { id: doc.market.id, symbol: doc.market.symbol } as Market;
}

function drive(doc: Fixture): Book | Tape {
  const aggregator = doc.type === "Book"
    ? new Book(market(doc))
    : new Tape(market(doc));

  doc.steps.forEach((step, index) => {
    if (step.clear) aggregator.clear();

    const orders = step.orders.map(parseOrder) as Order[];

    // A step that declares `refused` must raise rather than apply: an order that names no side cannot be placed on a book, and guessing one files it under the offer side silently. The step after it asserts the book was left alone.
    if (step.refused !== undefined) {
      assert.throws(() => aggregator.update(orders), (e: Error) =>
        e.message.includes(step.refused as string),
        `step ${index} (${step.note ?? ""}) must be refused, not guessed at`);
      return;
    }

    const added = aggregator.update(orders);

    // What update() reports it added is what Desk dispatches onTrade
    // from. A step that declares `adds` pins it — including the zero, which is
    // the update a handler must stay silent through.
    if (step.adds !== undefined) {
      const count = added instanceof Map
        ? [...added.values()].reduce((n, t) => n + t.length, 0)
        : (added as unknown[]).length;
      assert.equal(count, step.adds,
        `step ${index} (${step.note ?? ""}) reported ${count} new trades, ` +
          `expected ${step.adds}`);
    }
  });
  return aggregator;
}

function epochMilli(value: Date | null, where: string): number {
  assert.notEqual(value, null, `${where}: expected an instant, got null`);
  return value!.getTime();
}

function checkTrade(actual: Trade, expected: Record<string, any>, where: string): void {
  const readers: Record<string, (t: Trade) => unknown> = {
    price: (t) => t.price,
    units: (t) => t.units,
    restingId: (t) => t.resting.id,
    aggressorId: (t) => t.aggressor.id,
    restingOwnerId: (t) => t.resting.ownerId,
    aggressorOwnerId: (t) => t.aggressor.ownerId,
  };
  for (const [key, want] of Object.entries(expected)) {
    if (key === "at") {
      assert.equal(
        epochMilli(actual.at, `${where}.at`), (want as any).epochMilli,
        `${where}.at is the wrong side's stamp — the trade happened when the ` +
          `aggressor arrived, not when the quote it took was posted`,
      );
      continue;
    }
    assert.ok(readers[key], `${where}: fixture asks for unknown key ${key}`);
    assert.deepEqual(readers[key]!(actual), want, `${where}.${key}`);
  }
}

function checkOrderBook(book: Book, expect: Record<string, any>): void {
  const readers: Record<string, () => unknown> = {
    bestBuyPrice: () => book.bestBuyPrice(),
    bestBuyUnits: () => book.bestBuyUnits(),
    bestSellPrice: () => book.bestSellPrice(),
    bestSellUnits: () => book.bestSellUnits(),
    hasValueBuy: () => book.hasValue("BUY"),
    hasValueSell: () => book.hasValue("SELL"),
    buyLevels: () => book.buyLevels().map(([p, u]) => [p, u]),
    sellLevels: () => book.sellLevels().map(([p, u]) => [p, u]),
  };
  for (const [key, want] of Object.entries(expect)) {
    assert.ok(readers[key], `fixture asks for unknown key ${key}`);
    assert.deepEqual(readers[key]!(), want, key);
  }
}

function checkTrades(tape: Tape, expect: Record<string, any>): void {
  const held = tape.mostRecentTrades();

  if ("size" in expect) assert.equal(tape.size(), expect["size"]);

  if ("trades" in expect) {
    assert.equal(held.length, expect["trades"].length,
      `tape holds ${held.length} trades, expected ${expect["trades"].length}`);
    held.forEach((actual, i) => checkTrade(actual, expect["trades"][i], `trades[${i}]`));
  }

  if ("last" in expect) {
    if (expect["last"] === null) {
      assert.equal(tape.last(), null);
    } else {
      assert.notEqual(tape.last(), null, "last() is null but the tape is not empty");
      checkTrade(tape.last()!, expect["last"], "last()");
    }
  }

  // Last, because it empties the tape.
  if ("drain" in expect) {
    assert.equal(tape.drain().length, expect["drain"].count);
    assert.equal(tape.size(), expect["drain"].sizeAfter);
    assert.equal(tape.last(), null);
    assert.deepEqual(tape.drain(), []);
  }
}

for (const file of FILES) {
  const doc: Fixture = JSON.parse(readFileSync(DIR + file, "utf8"));

  test(`behaviour: ${file.replace(/\.json$/, "")}`, () => {
    const delivered = doc.steps.flatMap((s) => s.orders.map((o) => o["id"]));
    assert.deepEqual(
      delivered, doc.deliveredIds,
      "the fixture's input is not in the order it declares. deliveredIds is " +
        "what stops a case being quietly reordered into one that proves nothing " +
        "— the trades-ordering fixture is only a test at all because its input " +
        "arrives newest first.",
    );

    const aggregator = drive(doc);
    if (doc.type === "Book") checkOrderBook(aggregator as Book, doc.expect);
    else checkTrades(aggregator as Tape, doc.expect);
  });
}

test("there are behaviour fixtures to run", () => {
  assert.ok(FILES.length >= 4, `only found ${FILES.length} behaviour fixtures`);
});

test("every behaviour fixture type has an aggregator", () => {
  const types = new Set(FILES.map((f) => JSON.parse(readFileSync(DIR + f, "utf8")).type));
  const unmapped = [...types].filter((t) => !AGGREGATOR_TYPES.has(t));
  assert.deepEqual(unmapped, [], `fixtures exist for ${unmapped}, which nothing here drives`);
});
