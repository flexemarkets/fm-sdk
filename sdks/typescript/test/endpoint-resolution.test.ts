/**
 * Endpoint resolution is pure (no network): a bare marketplace id expands to
 * the default production host, while a full URL is preserved for development.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { resolveEndpoint } from "../src/client.ts";

test("a bare marketplace id resolves to the default production host", () => {
  assert.deepEqual(resolveEndpoint("2540"), {
    endpoint: "https://api.flexemarkets.com/api/marketplaces/2540",
  });
});

test("a full URL is preserved", () => {
  const url = "http://localhost:8080/api/marketplaces/2540";
  assert.deepEqual(resolveEndpoint(url), { endpoint: url });
});
