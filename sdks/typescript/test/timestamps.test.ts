/**
 * The server spells a moment two ways, and JavaScript gets one of them wrong by
 * default.
 *
 * Audit fields arrive bare — `"2017-04-11T00:54:35.135"` — because they come
 * from a Java LocalDateTime. `expiresAt` arrives `"2026-08-15T18:00:00Z"`
 * because it comes from an Instant. Both sampled from api.flexemarkets.com.
 *
 * `new Date` is specified to read a date-time with no zone as *local*. A bare
 * timestamp from this server is UTC, so the language's default is adrift by the
 * reader's offset: right in UTC, right in CI, wrong on a laptop in Denver. The
 * tests below force TZ so the difference is visible rather than assumed.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { toInstant } from "../src/timestamps.ts";

test("a bare timestamp is read as UTC", () => {
  assert.equal(
    toInstant("2017-04-11T00:54:35.135")?.toISOString(),
    "2017-04-11T00:54:35.135Z",
  );
});

test("fractional precision varies", () => {
  assert.equal(toInstant("2026-05-16T07:44:50.804")?.toISOString(), "2026-05-16T07:44:50.804Z");
  assert.equal(toInstant("2026-05-16T07:44:50")?.toISOString(), "2026-05-16T07:44:50.000Z");
});

test("a zoned timestamp is taken as given", () => {
  assert.equal(toInstant("2026-08-15T18:00:00Z")?.toISOString(), "2026-08-15T18:00:00.000Z");
});

test("an offset is honoured, not shifted again", () => {
  assert.equal(toInstant("2026-08-15T19:00:00+01:00")?.toISOString(), "2026-08-15T18:00:00.000Z");
});

/**
 * The whole point. This is what `new Date(bare)` returns in Denver, and it is
 * what the SDK used to hand every caller the raw string to reproduce.
 */
test("the language's own default would be adrift, and this is not", () => {
  const bare = "2017-04-11T00:54:35.135";
  const previousTz = process.env.TZ;
  try {
    process.env.TZ = "America/Denver";
    assert.equal(toInstant(bare)?.toISOString(), "2017-04-11T00:54:35.135Z");
  } finally {
    process.env.TZ = previousTz;
  }
});

/**
 * An Invalid Date is worse than null: it spreads, comparing false against
 * everything and surfacing as "Invalid Date" somewhere far from the parse.
 */
test("an unreadable value is null, not an Invalid Date", () => {
  assert.equal(toInstant("not a date"), null);
  assert.equal(toInstant(""), null);
  assert.equal(toInstant("   "), null);
  assert.equal(toInstant(null), null);
  assert.equal(toInstant(undefined), null);
});
