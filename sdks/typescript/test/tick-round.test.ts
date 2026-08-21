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

import { priceRound, tickRound, unitRound } from "../src/types.ts";
import type { Market } from "../src/types.ts";

/** The arithmetic this replaced, kept so the defect is demonstrable. */
function legacyRound(value: number, minimum: number, maximum: number, tick: number): number {
  return Math.min(Math.max(value - (value % tick), minimum), maximum);
}

/**
 * The server's own rule, from OrderDtoConverter — the oracle. A value must lie
 * within its bounds and, unless the dimension is fixed, sit on a tick measured
 * from the minimum.
 */
function serverWouldAccept(value: number, minimum: number, maximum: number, tick: number): boolean {
  if (value < minimum || value > maximum) return false;
  return tick <= 0 || (value - minimum) % tick === 0;
}

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

test("units round onto their own grid", () => {
  // The server refuses an off-tick size just as it does an off-tick price.
  const odd = { ...market(100, 200, 25), unitMinimum: 3, unitMaximum: 97, unitTick: 5 };

  assert.equal(unitRound(odd, 20), 18, "legal sizes are 3, 8, 13, 18, 23");
  assert.equal(unitRound(odd, 1), 3);
  assert.equal(unitRound(odd, 1_000), 93, "the highest legal tick, not 97");
});

test("both dimensions share the grid", () => {
  const square = { ...market(110, 199, 25), unitMinimum: 110, unitMaximum: 199, unitTick: 25 };

  assert.equal(unitRound(square, 137), priceRound(square, 137));
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

// --- the defects, demonstrated ----------------------------------------------

test("the old arithmetic produced prices the server refuses", () => {
  const [minimum, maximum, tick] = [110, 199, 25];

  const legacy = legacyRound(137, minimum, maximum, tick);
  assert.equal(legacy, 125);
  assert.equal(serverWouldAccept(legacy, minimum, maximum, tick), false,
    "125 is inside the bounds and off the tick");

  const fixed = tickRound(137, minimum, maximum, tick);
  assert.equal(fixed, 135);
  assert.ok(serverWouldAccept(fixed, minimum, maximum, tick));
});

test("clamping to the maximum would also be refused", () => {
  const [minimum, maximum, tick] = [110, 199, 25];

  assert.equal(serverWouldAccept(maximum, minimum, maximum, tick), false,
    "199 is the ceiling and is not itself a legal price");
  assert.equal(tickRound(210, minimum, maximum, tick), 185);
});

test("the old arithmetic returned NaN on a fixed dimension", () => {
  assert.ok(Number.isNaN(legacyRound(137, 150, 150, 0)),
    "a modulo by zero is NaN in JavaScript, which then spreads silently");

  assert.equal(tickRound(137, 150, 150, 0), 150);
});

test("every rounded value would be accepted by the server", () => {
  const grids: Array<[number, number, number]> = [
    [110, 199, 25],
    [100, 200, 25],
    [3, 97, 5],
    [150, 150, 0],
    [0, 1000, 1],
  ];

  for (const [minimum, maximum, tick] of grids) {
    for (let value = -50; value <= maximum + 50; value++) {
      const rounded = tickRound(value, minimum, maximum, tick);
      assert.ok(serverWouldAccept(rounded, minimum, maximum, tick),
        `grid [${minimum},${maximum}]/${tick} rounded ${value} to ${rounded}`);
    }
  }
});
