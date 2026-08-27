/**
 * Every shared fixture, through every parser that claims to produce its type.
 *
 * See `sdks/fixtures/README.md`. The short version: check-parity.py compares
 * the three SDKs' *declarations* and can only see field names, which is how
 * `approval` stayed wrong in two of them while the check reported ok. These
 * compare values.
 *
 * And they run each fixture through *both* parsers where there are two.
 * `Session` is parsed once in client.ts for REST and once in stomp.ts for the
 * WebSocket, and the two are byte-identical copies with nothing holding them
 * that way. Python had the same pair and they had already drifted: the
 * WebSocket one returned raw strings where REST returned instants.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  parseAccount, parseConnection, parseHolding, parseMarket, parseMarketplace,
  parseOrder, parsePerson, parseSecurity, parseSession, parseToken,
  embeddedOrders,
} from "../src/client.ts";
import { parseSession as parseSessionOverWs } from "../src/stomp.ts";

type Json = Record<string, unknown>;

const FIXTURE_DIR = join(fileURLToPath(new URL(".", import.meta.url)), "..", "..", "fixtures");

/** type -> the parsers that must all agree about it, named so a failure says which. */
const PARSERS: Record<string, [string, (data: Json) => unknown][]> = {
  Order: [["client", (d) => parseOrder(d)]],
  // Not a parser but a shape the SDK has to recognise, which is why it has
  // broken twice -- and silently here both times, since `._embedded` on an
  // array is undefined rather than an error.
  OrdersSnapshot: [["client", (d) => ({ orders: embeddedOrders(d).map((o) => parseOrder(o)) })]],
  Session: [["client", (d) => parseSession(d)], ["stomp", (d) => parseSessionOverWs(d)]],
  Holding: [["client", (d) => parseHolding(d)]],
  Account: [["client", (d) => parseAccount(d)]],
  Person: [["client", (d) => parsePerson(d)]],
  Market: [["client", (d) => parseMarket(d)]],
  Marketplace: [["client", (d) => parseMarketplace(d)]],
  ClientConnection: [["client", (d) => parseConnection(d)]],
  Security: [["client", (d) => parseSecurity(d)]],
  Token: [["client", (d) => parseToken(d)]],
};

interface Fixture {
  name: string;
  type: string;
  why: string;
  payload: Json;
  expect: Json;
}

const FIXTURES: Fixture[] = readdirSync(FIXTURE_DIR)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .map((f) => ({
    name: f.replace(/\.json$/, ""),
    ...(JSON.parse(readFileSync(join(FIXTURE_DIR, f), "utf8")) as Omit<Fixture, "name">),
  }));

function isInstant(v: unknown): v is { epochMilli: number } {
  return typeof v === "object" && v !== null && !Array.isArray(v)
    && Object.keys(v).length === 1 && "epochMilli" in v;
}

function field(parsed: unknown, wireName: string): unknown {
  assert.ok(parsed !== null && typeof parsed === "object", `expected an object, got ${parsed}`);
  assert.ok(wireName in (parsed as Json), `no field '${wireName}' on ${JSON.stringify(parsed)}`);
  return (parsed as Json)[wireName];
}

function check(parsed: unknown, expected: unknown, where: string): void {
  if (isInstant(expected)) {
    assert.ok(parsed !== null && parsed !== undefined, `${where}: expected an instant, got ${parsed}`);
    assert.ok(
      parsed instanceof Date,
      `${where}: expected a Date, got ${typeof parsed} ${JSON.stringify(parsed)} `
        + `-- the parser left the wire value unconverted`,
    );
    assert.ok(!Number.isNaN(parsed.getTime()), `${where}: Invalid Date`);
    assert.equal(
      parsed.getTime(), expected.epochMilli,
      `${where}: ${parsed.toISOString()} is ${parsed.getTime()}, expected ${expected.epochMilli} `
        + `(${new Date(expected.epochMilli).toISOString()}). A bare server timestamp means UTC; `
        + `new Date(bare) reads it as local and is right only in UTC.`,
    );
    return;
  }

  if (Array.isArray(expected)) {
    assert.ok(Array.isArray(parsed), `${where}: expected a list, got ${JSON.stringify(parsed)}`);
    assert.equal(parsed.length, expected.length, `${where}: wrong length`);
    expected.forEach((want, i) => {
      if (want !== null && typeof want === "object" && !Array.isArray(want)) {
        for (const [key, value] of Object.entries(want as Json)) {
          check(field(parsed[i], key), value, `${where}[${i}].${key}`);
        }
      } else {
        check(parsed[i], want, `${where}[${i}]`);
      }
    });
    return;
  }

  if (expected !== null && typeof expected === "object") {
    for (const [key, value] of Object.entries(expected as Json)) {
      check(field(parsed, key), value, `${where}.${key}`);
    }
    return;
  }

  // null must stay null: `?? false` and `?? 0` turn "undecided" into a decision.
  assert.equal(parsed, expected, `${where}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(parsed)}`);
}

for (const fixture of FIXTURES) {
  for (const [parserName, parse] of PARSERS[fixture.type] ?? []) {
    test(`${fixture.name} / ${parserName}`, () => {
      const parsed = parse(fixture.payload);
      assert.ok(parsed !== null && parsed !== undefined, `${parserName} returned ${parsed}`);
      for (const [key, expected] of Object.entries(fixture.expect)) {
        check(field(parsed, key), expected, `${fixture.name}/${parserName}.${key}`);
      }
    });
  }
}

test("there are fixtures to run", () => {
  // Guard the guard: a bad path would report everything passing.
  assert.ok(FIXTURES.length >= 10, `only found ${FIXTURES.length} fixtures`);
});

test("every fixture type has a parser", () => {
  // A fixture for a type nothing parses is a test that does not run.
  const unmapped = [...new Set(FIXTURES.map((f) => f.type))].filter((t) => !(t in PARSERS));
  assert.deepEqual(unmapped, [], `fixtures exist for types no parser here handles`);
});
