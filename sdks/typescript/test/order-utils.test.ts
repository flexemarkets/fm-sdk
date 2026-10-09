/**
 * The relational order helpers, held to what Java's OrdersTest holds.
 *
 * These three arrived in TypeScript and Python after existing in Java alone.
 * Java tests them; the other two did not test order utilities at all, so the
 * behaviour was pinned in one language and free to drift in two.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import { isConsumedOrSplit, isResting, isSubmit, isSupplier, limit } from "../src/order-utils.js";
import { OrderSide, OrderType, type Market, type Order } from "../src/types.js";

// Distinct ids on purpose: marketplaceId and marketId are both numbers and
// adjacent in meaning, so equal values would hide a transposition.
const market = (): Market =>
  ({ id: 77, marketplaceId: 42, symbol: "ALPHA" }) as Market;

const order = (id: number, supplier: number): Order =>
  ({ id, supplier }) as Order;

test("limit puts every value in the field it belongs in", () => {
  const o = limit(market(), OrderSide.BUY, 3, 250);

  assert.equal(o.marketplaceId, 42);
  assert.equal(o.marketId, 77);
  assert.equal(o.symbol, "ALPHA");
  assert.equal(o.side, OrderSide.BUY);
  assert.equal(o.units, 3);
  assert.equal(o.price, 250);
  assert.equal(o.type, OrderType.LIMIT);
});

test("limit does not confuse marketId with marketplaceId", () => {
  const o = limit(market(), OrderSide.SELL, 1, 10);

  assert.notEqual(o.marketId, o.marketplaceId);
  assert.equal(o.marketId, 77);
  assert.equal(o.marketplaceId, 42);
});

test("limit does not confuse units with price", () => {
  const o = limit(market(), OrderSide.BUY, 2, 900);

  assert.equal(o.units, 2);
  assert.equal(o.price, 900);
});

test("limit leaves server-assigned fields empty", () => {
  const o = limit(market(), OrderSide.BUY, 1, 10);

  assert.equal(o.id, 0);
  assert.equal(o.original, 0);
  assert.equal(o.supplier, 0);
  assert.equal(o.consumer, null);
});

test("limit carries the side it is given", () => {
  assert.equal(limit(market(), OrderSide.SELL, 1, 10).side, OrderSide.SELL);
  assert.equal(limit(market(), OrderSide.BUY, 1, 10).side, OrderSide.BUY);
});

test("isConsumedOrSplit is true once there is a consumer", () => {
  assert.equal(isConsumedOrSplit({ consumer: null } as Order), false);
  assert.equal(isConsumedOrSplit({ consumer: 9 } as Order), true);
});

test("isConsumedOrSplit tolerates a null order", () => {
  assert.equal(isConsumedOrSplit(null), false);
});

test("isSupplier matches an order against the one that supplied it", () => {
  const maker = order(100, 0);
  const taker = order(200, 100);

  assert.equal(isSupplier(maker, taker), true);
  assert.equal(isSupplier(taker, maker), false);
});

test("isSupplier tolerates nulls", () => {
  assert.equal(isSupplier(null, order(1, 0)), false);
  assert.equal(isSupplier(order(1, 0), null), false);
  assert.equal(isSupplier(null, null), false);
});

const row = (id: number, original: number, supplier: number, consumer: number | null,
             type = "LIMIT"): Order =>
  ({ id, original, supplier, consumer, type }) as Order;

test("isSubmit is true of an order as it was placed, and of a cancel", () => {
  assert.equal(isSubmit(row(10, 10, 10, null)), true);
  assert.equal(isSubmit(row(11, 10, 10, null)), false, "a remainder split off order 10");
  assert.equal(isSubmit(row(12, 12, 10, null)), false, "supplied by another order");
  assert.equal(isSubmit(row(14, 10, 14, null)), false, "the remainder of order 10");
  assert.equal(isSubmit(row(13, 10, 10, 10, "CANCEL")), true);
});

test("a split order rests when the order that took its remainder came after it", () => {
  // Order 10 was split (consumer 0); its remainder, 11, was taken by 20. A
  // split order's standing is its remainder's: taken by a younger order, it
  // was the resting side.
  const split = row(10, 10, 10, 0);
  const remainder = row(11, 10, 10, 20);
  const taker = row(20, 20, 20, 11);
  assert.equal(isResting([split, remainder, taker], split), true);
});

test("a split order does not rest when the order that took its remainder came before it", () => {
  const split = row(10, 10, 10, 0);
  const remainder = row(11, 10, 10, 5);
  const taker = row(5, 5, 5, 11);
  assert.equal(isResting([split, remainder, taker], split), false);
});

test("a split order with no remainder in the batch does not rest", () => {
  // The batch's other trade -- 30 resting, taken by the younger 40 -- is not
  // the split order's remainder, and must not answer for it.
  const split = row(10, 10, 10, 0);
  const other = [row(30, 30, 30, 40), row(40, 40, 40, 30)];
  assert.equal(isResting([...other, split], split), false);
});
