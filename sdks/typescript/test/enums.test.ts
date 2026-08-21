/**
 * Side and OrderType in TypeScript, where a literal union makes the type nearly
 * free.
 *
 * Java's enums are a hard break: a caller passing "BUY" stops compiling. A TS
 * `enum` would break the same way, being a nominal type. A literal union does
 * not — the values stay ordinary strings, so they serialise with no encoder,
 * compare equal to the wire spelling, and existing callers keep working — while
 * `"BYU"` is still a type error, which is what the old ORDER_SIDE_BUY constant
 * could only suggest.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { OrderType, Side, toOrderType, toSide } from "../src/types.ts";
import { contra, isBuy, isCancel, isLimit, isSell } from "../src/order-utils.ts";
import type { Order } from "../src/types.ts";

test("a member is an ordinary string", () => {
  assert.equal(Side.BUY, "BUY");
  assert.equal(JSON.stringify({ side: Side.BUY }), '{"side":"BUY"}');
});

test("a side is read whatever its casing", () => {
  assert.equal(toSide("BUY"), "BUY");
  assert.equal(toSide("buy"), "BUY");
  assert.equal(toSide(" Sell "), "SELL");
});

test("an unknown value is null rather than a throw", () => {
  assert.equal(toSide("BYU"), null);
  assert.equal(toSide(null), null);
  assert.equal(toOrderType("MARKET"), null, "the server has sent this");
});

test("contra pairs the two sides", () => {
  assert.equal(contra(Side.BUY), "SELL");
  assert.equal(contra("sell"), "BUY", "a plain string still works");
});

test("the helpers take either a member or a loose string", () => {
  assert.ok(isBuy(Side.BUY) && isBuy("buy"));
  assert.ok(isSell("SELL") && !isSell(Side.BUY));

  const limit = { type: "LIMIT", side: "BUY" } as Order;
  assert.ok(isLimit(limit) && !isCancel(limit));

  const cancel = { type: OrderType.CANCEL, side: null } as Order;
  assert.ok(isCancel(cancel) && !isLimit(cancel));
});
