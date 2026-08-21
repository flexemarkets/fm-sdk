/**
 * Rounding a price onto the market's tick grid.
 *
 * The grid is anchored at `priceMinimum`, because that is what the server
 * checks: `(price - priceMinimum) % priceTick`. This subtracted
 * `price % priceTick`, anchoring at zero — right whenever the floor is a
 * multiple of the tick, and wrong for the rest in a way that produces a
 * plausible number rather than an error.
 *
 * Same defect as B20's, in a different copy of the same rule. Mirrors 2212858.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { priceRound } from "../src/types.ts";
import type { Market } from "../src/types.ts";

function market(minimum: number, maximum: number, tick: number): Market {
  return {
    id: 11, marketplaceId: 1, name: "Stock", description: null, symbol: "STK",
    privateMarket: false, priceMinimum: minimum, priceMaximum: maximum,
    priceTick: tick, unitMinimum: 1, unitMaximum: 100, unitTick: 1,
  };
}

test("a grid anchored at a multiple of the tick", () => {
  assert.equal(priceRound(market(100, 200, 25), 137), 125);
  assert.equal(priceRound(market(100, 200, 25), 125), 125);
});

test("a grid anchored away from zero", () => {
  // 110/135/160/185 are legal; the old code gave 125 for 137.
  const stock = market(110, 199, 25);
  assert.equal(priceRound(stock, 137), 135);
  assert.equal(priceRound(stock, 199), 185);
  assert.equal(priceRound(stock, 110), 110);
});

test("prices outside the range are clamped", () => {
  const stock = market(110, 199, 25);
  assert.equal(priceRound(stock, 5), 110);
  assert.equal(priceRound(stock, 10_000), 185);
});

test("a fixed price market has one legal price", () => {
  // A tick of zero used to divide by zero and return NaN.
  const fixed = market(150, 150, 0);
  assert.equal(priceRound(fixed, 137), 150);
  assert.equal(priceRound(fixed, 9_999), 150);
});

test("every result sits on the grid", () => {
  const stock = market(110, 199, 25);
  for (let price = 0; price <= 300; price++) {
    const rounded = priceRound(stock, price);
    assert.equal((rounded - stock.priceMinimum) % stock.priceTick, 0,
      `rounding ${price} gave ${rounded}`);
    assert.ok(rounded >= stock.priceMinimum && rounded <= stock.priceMaximum);
  }
});
