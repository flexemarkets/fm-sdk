/**
 * The User-Agent names the version installed, not 0.0.0 (fm-server#1012).
 *
 * The version used to come from the repository's VERSION file, three levels
 * above the module. That file exists only in a checkout: installed from npm,
 * the module sits in node_modules/@flexemarkets/fm-sdk/dist/ with package.json
 * beside dist/, and three levels up is the consumer's node_modules -- so every
 * npm install reported fm-sdk-typescript/0.0.0.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { Flexemarkets } from "../src/client.ts";
import { readVersion } from "../src/version.ts";

test("reads the version from package.json in the installed layout", () => {
  const root = mkdtempSync(join(tmpdir(), "fm-sdk-installed-"));
  const pkg = join(root, "node_modules", "@flexemarkets", "fm-sdk");
  mkdirSync(join(pkg, "dist"), { recursive: true });
  writeFileSync(join(pkg, "package.json"), JSON.stringify({ name: "@flexemarkets/fm-sdk", version: "9.8.7" }));

  assert.equal(readVersion(pathToFileURL(join(pkg, "dist") + "/")), "9.8.7");
});

test("reads this package's own version from a checkout", () => {
  const own = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf-8"));
  assert.equal(readVersion(), own.version);
});

test("falls back to 0.0.0 when there is no package.json", () => {
  const empty = mkdtempSync(join(tmpdir(), "fm-sdk-none-"));
  mkdirSync(join(empty, "dist"));
  assert.equal(readVersion(pathToFileURL(join(empty, "dist") + "/")), "0.0.0");
});

test("the wire carries it", async () => {
  const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
  const own = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf-8"));
  const agents: string[] = [];

  const server = http.createServer((req, res) => {
    agents.push(String(req.headers["user-agent"] ?? ""));
    res.writeHead(200, { "Content-Type": "application/json" });
    if (req.url === "/api/tokens/refresh") {
      res.end(JSON.stringify({
        token: TOKEN,
        person: { id: 7, accountId: 1, email: "dev@dev" },
        account: { id: 1, name: "dev" },
      }));
    } else {
      res.end("[]");
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "user-agent-test");
    // Signing in is one request; a call is another, sent by a different helper.
    await fm.marketplaces();
    await fm.close();
  } finally {
    server.close();
  }

  assert.equal(agents.length, 2, "the sign-in and the call");
  assert.deepEqual([...new Set(agents)], [`fm-sdk-typescript/${own.version}`]);
});
