/**
 * Endpoint resolution is pure (no network): a bare marketplace id expands to
 * the default production host, while a full URL is preserved for development.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { resolveEndpoint, server } from "../src/client.ts";

// The API root is derived by hand in Java, Python and TypeScript, and nothing
// else holds the three together: check-parity.py compares wire fields and
// method surfaces, and this is neither -- `server` is private in all three.
// That is how 0.1.1 shipped fixed in Java and unchanged here.
//
// See sdks/fixtures/endpoints/README.md.
const ENDPOINT_DIR = join(
  fileURLToPath(new URL(".", import.meta.url)),
  "..", "..", "fixtures", "endpoints",
);

const ENDPOINT_FIXTURES = readdirSync(ENDPOINT_DIR)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .map((f) => ({
    name: f.replace(/\.json$/, ""),
    ...JSON.parse(readFileSync(join(ENDPOINT_DIR, f), "utf8")),
  }));

test("a bare marketplace id resolves to the default production host", () => {
  assert.deepEqual(resolveEndpoint("2540"), {
    endpoint: "https://api.flexemarkets.com/api/marketplaces/2540",
  });
});

test("a full URL is preserved", () => {
  const url = "http://localhost:8080/api/marketplaces/2540";
  assert.deepEqual(resolveEndpoint(url), { endpoint: url });
});

for (const fixture of ENDPOINT_FIXTURES) {
  test(`api root: ${fixture.name}`, () => {
    assert.equal(server(fixture.endpoint), fixture.apiRoot, fixture.why);
  });
}

test("there are endpoint fixtures to run", () => {
  // Guard the guard: a bad path would report everything passing.
  assert.ok(ENDPOINT_FIXTURES.length >= 6, `only found ${ENDPOINT_FIXTURES.length}`);
});

test("FM_URL overrides the host for a bare id", () => {
  // What makes `-E 123` usable against a development server, as in Java.
  const previous = process.env.FM_URL;
  process.env.FM_URL = "http://localhost:8080";
  try {
    assert.deepEqual(resolveEndpoint("7"), {
      endpoint: "http://localhost:8080/api/marketplaces/7",
    });
  } finally {
    if (previous === undefined) delete process.env.FM_URL;
    else process.env.FM_URL = previous;
  }
});
