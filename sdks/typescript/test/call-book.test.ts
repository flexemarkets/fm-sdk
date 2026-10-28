/**
 * Only a call market shows an indicative price. A continuous market stores a
 * book setting too, which means nothing for it, and reading the setting
 * alone would promise an indicative price the server refuses to give.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { parseMarket } from "../src/client.ts";
import { showsIndicative } from "../src/types.ts";

const base = { id: 12, marketplaceId: 3050, symbol: "BAT" };

test("only an indicative call market shows its indicative price", () => {
  assert.equal(showsIndicative(parseMarket({ ...base, mechanism: "CALL", callBook: "INDICATIVE" })), true);
  assert.equal(showsIndicative(parseMarket({ ...base, mechanism: "CALL", callBook: "SEALED" })), false);
  assert.equal(showsIndicative(parseMarket({ ...base, mechanism: "CONTINUOUS", callBook: "INDICATIVE" })), false,
    "the book setting means nothing without calls");
});

test("a call market that does not say is sealed", () => {
  const market = parseMarket({ ...base, mechanism: "CALL" });
  assert.equal(market.callBook, "SEALED");
  assert.equal(showsIndicative(market), false);
});
