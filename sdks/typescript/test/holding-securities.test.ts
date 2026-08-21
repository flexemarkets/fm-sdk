/**
 * Reading a holding's positions.
 *
 * `getSecurity` threw for a market the holder had no position in, which is what
 * every holding looks like before the first allocation — ordinary, not
 * exceptional. And `securities` arrived in whatever order the server listed
 * them while `holdingUnits` sorted on the way out, so two reads of the same
 * holding disagreed about order.
 *
 * Mirrors e4d8151 (Java) and 2643731 (Python). TypeScript has no constructor to
 * normalise in, so the ordering is applied where a Holding is parsed.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { getSecurity, holdingUnits, orderedSecurities } from "../src/types.ts";
import type { Holding, Security } from "../src/types.ts";

function security(marketId: number, units = 1): Security {
  return {
    marketId, units, availableUnits: units, shortUnits: 0,
    canBuy: true, canSell: true,
  };
}

function holding(securities: Security[]): Holding {
  return {
    marketplaceId: 1, sessionId: 300, allocationId: 42, ownerId: 8,
    name: "alice", cash: 10_000, availableCash: 10_000,
    securities: orderedSecurities(securities),
  };
}

test("positions come back in market order", () => {
  const out = holding([security(30), security(10), security(20)]);

  assert.deepEqual(out.securities.map((s) => s.marketId), [10, 20, 30]);
});

test("no positions reads as empty rather than null", () => {
  assert.deepEqual(orderedSecurities(null), []);
  assert.deepEqual(orderedSecurities(undefined), []);
});

test("asking for a position the holder does not have is null, not a throw", () => {
  const out = holding([security(10, 5)]);

  assert.equal(getSecurity(out, 10)?.units, 5);
  assert.equal(getSecurity(out, 99), null);
});

test("units follow the same order", () => {
  assert.deepEqual(holdingUnits(holding([security(30, 3), security(10, 1)])), [1, 3]);
});
