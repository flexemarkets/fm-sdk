/**
 * Where a client's account, credentials and endpoint come from when the caller
 * does not pass them: ~/.fm/credential, then ~/.fm/endpoint, then FM_API_URL,
 * then production. test/isolated-home.ts gives each file an empty home.
 */

import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";

import { DEFAULT_ENDPOINT, loadConfig, loadPropertiesFile } from "../src/client.ts";

function fmDir(): string {
  const dir = join(homedir(), ".fm");
  mkdirSync(dir, { recursive: true });
  return dir;
}

afterEach(() => {
  process.env.HOME = mkdtempSync(join(tmpdir(), "fm-sdk-test-home-"));
  delete process.env.FM_API_URL;
});

test("with no files and no override the endpoint is production", () => {
  assert.deepEqual(loadConfig(), { endpoint: DEFAULT_ENDPOINT });
});

test("the credential and endpoint files are read", () => {
  writeFileSync(join(fmDir(), "credential"), "account=lab\nemail=ada@lab.edu\npassword=pw\n");
  writeFileSync(join(fmDir(), "endpoint"), "endpoint=http://localhost:8080/api/marketplaces/7\n");

  assert.deepEqual(loadConfig(), {
    account: "lab",
    email: "ada@lab.edu",
    password: "pw",
    endpoint: "http://localhost:8080/api/marketplaces/7",
  });
});

test("FM_API_URL overrides the endpoint file", () => {
  writeFileSync(join(fmDir(), "endpoint"), "endpoint=http://localhost:8080/api/marketplaces/7\n");
  process.env.FM_API_URL = "http://localhost:9090/api/marketplaces/8";

  assert.equal(loadConfig().endpoint, "http://localhost:9090/api/marketplaces/8");
});

test("a properties file skips comments, blank lines and lines without a value", () => {
  const path = join(mkdtempSync(join(tmpdir(), "fm-sdk-props-")), "credential");
  writeFileSync(path, "# account=other\n\n  account = lab  \nnot a property\npassword=a=b\n");

  // Split on the first '=' only: a password may contain one.
  assert.deepEqual(loadPropertiesFile(path), { account: "lab", password: "a=b" });
});

test("a missing properties file is empty", () => {
  assert.deepEqual(loadPropertiesFile(join(tmpdir(), "fm-sdk-no-such-file")), {});
});
