/**
 * The one human label a Person record can always produce.
 *
 * Every consumer of `users()` was writing this join by hand. None of the three
 * fields it reads can be relied on alone. The same cases run in the Java and
 * Python suites.
 *
 * A function here rather than a method, because `Person` is a plain wire
 * interface in this SDK — the same thing Java puts on the record and Python on
 * the dataclass.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { displayName } from "../src/types.js";
import type { Person } from "../src/types.js";

function person(firstName: string | null, lastName: string | null, email: string | null): Person {
  return { firstName, lastName, email } as Person;
}

const CASES: Array<[string | null, string | null, string | null, string | null]> = [
  ["Ada", "Lovelace", "ada@example.com", "Ada Lovelace"],
  ["Ada", null, "ada@example.com", "Ada"],
  [null, "Lovelace", "ada@example.com", "Lovelace"],
  ["  Ada  ", "  Lovelace  ", null, "Ada Lovelace"],
  [null, null, "ada@example.com", "ada@example.com"],
  ["", "", "ada@example.com", "ada@example.com"],
  ["   ", "   ", "  ada@example.com  ", "ada@example.com"],
  [null, null, null, null],
  [null, null, "   ", null],
];

for (const [first, last, email, expected] of CASES) {
  test(`displayName(${first}, ${last}, ${email}) -> ${expected}`, () => {
    assert.equal(displayName(person(first, last, email)), expected);
  });
}

test("a name wins over the email", () => {
  // Not "Name <email>". That is a presentation choice, and a caller who wants
  // it composes one — baking it in would make the common case wrong.
  const label = displayName(person("Ada", "Lovelace", "ada@example.com"));
  assert.equal(label, "Ada Lovelace");
  assert.ok(!label!.includes("ada@example.com"));
});
