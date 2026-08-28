/**
 * The trade tape: which side of a match it names, and what order it holds.
 *
 * Both properties failed silently before `Trade` existed, which is why they are
 * asserted against a shared fixture rather than left to reading the code:
 *
 * - the tape kept the *resting* order of each pair and dropped the incoming
 *   one, so a caller asking who took a trade got the maker — a real
 *   participant, at a real price, in an answer with nothing wrong on its face;
 * - it appended in the order the array arrived, and the
 *   `/v1/orders/recent-trades` snapshot that seeds it — on open, on a sequence
 *   gap, and after every reconnect — arrives newest *first*, so the tape was
 *   backwards and `mostRecentTrades().at(-1)` was the oldest trade retained.
 *
 * `sdks/fixtures/trades/pairing.json` is the same fixture the Java and Python
 * suites read, so the three cannot drift on the answer.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { parseOrder } from "../src/client.js";
import { Trades } from "../src/trades.js";
import type { Market } from "../src/types.js";

const FIXTURE = JSON.parse(
  readFileSync(
    fileURLToPath(new URL("../../fixtures/trades/pairing.json", import.meta.url)),
    "utf8",
  ),
);

function market(): Market {
  return { id: FIXTURE.market.id, symbol: FIXTURE.market.symbol } as Market;
}

function tape(): Trades {
  const trades = new Trades(market(), 100);
  trades.update(FIXTURE.orders.map(parseOrder));
  return trades;
}

test("the fixture delivers the newer trade's pair first, as the snapshot does", () => {
  // Guard the guard: if the fixture already arrived oldest-first, the ordering
  // assertion below would pass on a tape that never sorted anything. The claim
  // is about the pairs, not the rows — within a pair the resting order
  // deliberately carries the later stamp, so that reading the time off the
  // wrong side shows up in the timestamp test.
  const ids = FIXTURE.orders.map((o: { id: number }) => o.id);
  const positions = FIXTURE.expect.map((e: { aggressorId: number }) =>
    ids.indexOf(e.aggressorId),
  );

  for (let i = 1; i < positions.length; i++) {
    assert.ok(
      positions[i] < positions[i - 1],
      `the fixture must deliver the newer trade's pair first; aggressors sit at ${positions}`,
    );
  }
});

test("the tape names the aggressor, not the resting order", () => {
  const actual = tape().mostRecentTrades();
  assert.equal(actual.length, FIXTURE.expect.length);

  actual.forEach((trade, i) => {
    const want = FIXTURE.expect[i];
    const where = `trade at ${want.price}`;

    assert.equal(
      trade.aggressor.ownerId,
      want.aggressorOwnerId,
      `${where}: named owner ${trade.aggressor.ownerId} as the aggressor, expected ` +
        `${want.aggressorOwnerId} — ${want.restingOwnerId} is the resting side`,
    );
    assert.equal(trade.resting.ownerId, want.restingOwnerId, where);
    assert.equal(trade.aggressor.id, want.aggressorId, where);
    assert.equal(trade.resting.id, want.restingId, where);
  });
});

test("price and units come from the resting side", () => {
  tape()
    .mostRecentTrades()
    .forEach((trade, i) => {
      assert.equal(trade.price, FIXTURE.expect[i].price);
      assert.equal(trade.units, FIXTURE.expect[i].units);
      assert.equal(trade.price, trade.resting.price);
    });
});

test("the time comes from the aggressor, not the quote it took", () => {
  // Each resting order in the fixture carries a later stamp than its
  // aggressor, so reading the wrong side is visible here.
  tape()
    .mostRecentTrades()
    .forEach((trade, i) => {
      assert.notEqual(trade.at, null);
      assert.equal(
        trade.at!.getTime(),
        FIXTURE.expect[i].at.epochMilli,
        "that is the resting order's stamp, not the aggressor's",
      );
    });
});

test("the tape is oldest first even when the snapshot is not", () => {
  const t = tape();

  assert.deepEqual(
    t.mostRecentPrices(),
    FIXTURE.expect.map((e: { price: number }) => e.price),
  );

  assert.notEqual(t.last(), null);
  assert.equal(
    t.last()!.price,
    FIXTURE.expect[FIXTURE.expect.length - 1].price,
    "last() must be the newest trade; a tape that appends in array order " +
      "returns the oldest one it retained",
  );
});

test("an empty tape has no last trade", () => {
  assert.equal(new Trades(market(), 100).last(), null);
});
