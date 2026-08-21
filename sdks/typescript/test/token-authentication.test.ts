/**
 * Connecting with a token rather than a password.
 *
 * This never worked. The SDK POSTed `/tokens` with the bearer header *and* a
 * body of `{"username": "|", "password": ""}`, and fm-server answers that with
 * 400 MESSAGE_NOT_READABLE — verified against a running server, where the same
 * POST with a real password returns 200. So every token connection failed, in
 * this SDK and the Python one, from the day each was written.
 *
 * A caller holding a token has no account, email or password to present. The
 * route that exists for them is `GET /tokens/refresh`, which validates the
 * token and returns the account and person behind it. The Java SDK has always
 * used it, and records that fm-lib-net carried the same branch and an earlier
 * rewrite dropped it — this is the third occurrence.
 *
 * Asserted against a loopback server rather than a stubbed fetch, because a
 * stub is precisely what hid it: a mock answers whatever the test tells it to,
 * and the server's 400 never appears.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

async function withServer(
  run: (base: string, requests: string[]) => Promise<void>,
): Promise<void> {
  const requests: string[] = [];

  const server = http.createServer((req, res) => {
    requests.push(`${req.method} ${req.url}`);

    if (req.method === "GET" && req.url === "/api/tokens/refresh") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        token: TOKEN,
        person: { id: 7, accountId: 1, email: "dev@dev" },
        account: { id: 1, name: "dev" },
      }));
      return;
    }
    if (req.method === "GET" && req.url === "/api") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ _links: {} }));
      return;
    }
    if (req.method === "POST") {
      // What fm-server actually answers a token POST with blanks.
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "MESSAGE_NOT_READABLE", path: req.url }));
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("[]");
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  try {
    await run(base, requests);
  } finally {
    server.close();
  }
}

test("a token connects through the refresh route", async () => {
  await withServer(async (base, requests) => {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "token-test");
    try {
      assert.equal(fm.user.id, 7);
      assert.equal(fm.account.name, "dev");
    } finally {
      await fm.close();
    }

    assert.ok(requests.includes("GET /api/tokens/refresh"), requests.join(", "));
  });
});

test("a token never posts to /tokens", async () => {
  // The POST is the defect. A server that refuses it must not be reached.
  await withServer(async (base, requests) => {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "token-test");
    await fm.close();

    assert.ok(
      !requests.some((r) => r.startsWith("POST /api/tokens")),
      requests.join(", "),
    );
  });
});
