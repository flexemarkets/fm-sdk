/**
 * A test never sees the ~/.fm of whoever runs it (test/isolated-home.ts).
 * Fails on a developer machine that has ~/.fm if the isolation is lost; on CI
 * there is no ~/.fm to see either way.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

test("a test runs with an empty home and no FM_* overrides", () => {
  assert.equal(existsSync(join(homedir(), ".fm")), false, `${homedir()} has a .fm`);
  assert.equal(process.env.FM_API_URL, undefined);
  assert.equal(process.env.FM_URL, undefined);
});
