/**
 * Decimals, held to the cases all three SDKs run.
 *
 * See sdks/fixtures/decimals/README.md.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { Decimals } from "../src/index.ts";

const FIXTURES = join(
  fileURLToPath(new URL(".", import.meta.url)),
  "..", "..", "fixtures", "decimals",
);

interface Case {
  value?: number;
  decimals: number;
  text: string;
  reason?: string;
}

function cases(name: string): Case[] {
  return JSON.parse(readFileSync(join(FIXTURES, name), "utf8")).cases;
}

for (const c of cases("format.json")) {
  test(`format: ${c.text} @${c.decimals}`, () => {
    assert.equal(Decimals.format(c.value!, c.decimals), c.text);
    assert.equal(Decimals.parse(c.text, c.decimals), c.value);
  });
}

for (const c of cases("parse.json")) {
  test(`parse: ${c.text} @${c.decimals}`, () => {
    assert.equal(Decimals.parse(c.text, c.decimals), c.value);
  });
}

for (const c of cases("refused.json")) {
  test(`refused: ${c.reason} @${c.decimals}`, () => {
    assert.throws(() => Decimals.parse(c.text, c.decimals), RangeError);
  });
}

test("there are fixtures to run", () => {
  for (const name of ["format.json", "parse.json", "refused.json"]) {
    assert.ok(cases(name).length >= 10, name);
  }
});

test("-0 parses as 0, not negative zero", () => {
  assert.ok(Object.is(Decimals.parse("-0", 2), 0));
  assert.ok(Object.is(Decimals.parse("-0.00", 2), 0));
});

test("beyond a safe integer is refused rather than approximated", () => {
  assert.equal(Decimals.parse("9007199254740991", 0), Number.MAX_SAFE_INTEGER);
  assert.throws(() => Decimals.parse("9007199254740992", 0), /out of range/);
  assert.throws(() => Decimals.parse("-9007199254740992", 0), /out of range/);
  assert.throws(() => Decimals.format(Number.MAX_SAFE_INTEGER + 1, 2), RangeError);
});

test("a fraction has already lost the exactness this keeps", () => {
  assert.throws(() => Decimals.format(50.5, 2), RangeError);
  assert.throws(() => Decimals.format(Number.NaN, 2), RangeError);
  assert.throws(() => Decimals.format(1, 1.5), RangeError);
  assert.throws(() => Decimals.format(1, -1), RangeError);
  assert.throws(() => Decimals.parse(50 as unknown as string, 2), RangeError);
});

test("says why it refused", () => {
  assert.throws(() => Decimals.parse("50.001", 2), /more than 2 decimal places/);
  assert.throws(() => Decimals.parse("1e2", 2), /not a decimal number/);
});

// The displays moved onto format printed (cents / 100).toFixed(2). Over the
// range a price takes the two agree character for character, so the move
// changes no output.
test("agrees with the cents display it replaced", () => {
  for (let cents = -20_000; cents <= 2_000_000; cents++) {
    const old = (cents / 100).toFixed(2).padStart(5);
    const now = Decimals.format(cents, Decimals.DEFAULT_PRICE_DECIMALS).padStart(5);
    if (old !== now) assert.equal(now, old, `at ${cents}`);
  }
});
