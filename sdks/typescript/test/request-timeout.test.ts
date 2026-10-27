/**
 * A request whose answer never comes is given up, as Python and Java give it up.
 *
 * fetch has no timeout of its own. A request the server never answered waited
 * for ever, and a robot stalled on one order stopped trading without a word.
 * An order given up this way is an answer lost, so it is sent once more as the
 * same submission (submission-id.test.ts).
 */
import { afterEach, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets, REQUEST_TIMEOUT_MS, requestTimeout } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

beforeEach(() => (requestTimeout.ms = 200));
afterEach(() => (requestTimeout.ms = REQUEST_TIMEOUT_MS));

/** A server that answers sign-in, and leaves the first `silent` other requests unanswered. */
async function serving(silent: number) {
  const orders: Array<Record<string, unknown>> = [];
  const held: http.ServerResponse[] = [];
  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      if ((req.url ?? "").startsWith("/api/v1/tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
        return;
      }
      if (raw) orders.push(JSON.parse(raw));
      if (silent-- > 0) {
        held.push(res);
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(req.method === "POST" ? { id: 42, marketplaceId: 1, marketId: 11, type: "LIMIT" } : []));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "request-timeout-test");
  const done = async () => {
    await fm.close();
    for (const res of held) res.destroy();
    server.closeAllConnections();
    server.close();
  };
  return { fm, orders, done };
}

test("the timeout is the other two SDKs' thirty seconds", () => {
  assert.equal(REQUEST_TIMEOUT_MS, 30_000);
});

test("a read whose answer never comes is given up", async () => {
  const { fm, done } = await serving(1);
  const started = Date.now();
  try {
    await assert.rejects(fm.markets(1), (e: Error) => e.name === "TimeoutError");
  } finally {
    await done();
  }
  assert.ok(Date.now() - started < 5_000, "given up within the timeout, not waited on");
});

test("an order whose answer never came is sent once more as the same submission", async () => {
  const { fm, orders, done } = await serving(1);
  try {
    assert.equal((await fm.submitLimit(1, 11, "BUY", 5, 135)).id, 42);
  } finally {
    await done();
  }
  assert.equal(orders.length, 2);
  assert.deepEqual(orders[1], orders[0]);
});

test("an order unanswered twice is reported", async () => {
  const { fm, orders, done } = await serving(2);
  try {
    await assert.rejects(fm.submitLimit(1, 11, "BUY", 5, 135), (e: Error) => e.name === "TimeoutError");
  } finally {
    await done();
  }
  assert.equal(orders.length, 2);
});
