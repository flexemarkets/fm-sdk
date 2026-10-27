/**
 * An order whose answer is lost is sent again, and trades once.
 *
 * A send that loses its connection may or may not have reached the exchange.
 * Reported as a failure, it invited the caller to place the order again, and if
 * the first had landed the position doubled with nothing anywhere saying so.
 * Each order now names its submission, and fm-server answers a submission it
 * already holds with the order it made (its OrderIdempotencyTest), so the
 * client can send it once more itself.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets, InvalidArgumentError } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

/**
 * A client and a server that does, with each order in turn, what `plan` says:
 * "lose" cuts the connection without an answer, a number refuses with that
 * status, and anything else, or nothing, accepts.
 */
async function serving(plan: Array<"lose" | number>) {
  const submitted: Array<Record<string, unknown>> = [];
  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      if ((req.url ?? "").startsWith("/api/v1/tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
        return;
      }
      submitted.push(JSON.parse(raw));
      const step = plan.shift();
      if (step === "lose") {
        req.socket.destroy();
        return;
      }
      const status = typeof step === "number" ? step : 200;
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(status === 200 ? { id: 42, marketplaceId: 1, marketId: 11, type: "LIMIT" }
                                            : { error: "ORDER_INVALID" }));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "submission-id-test");
  const done = async () => {
    await fm.close();
    server.close();
  };
  return { fm, submitted, done };
}

test("each order names its own submission", async () => {
  const { fm, submitted, done } = await serving([]);
  try {
    await fm.submitLimit(1, 11, "BUY", 5, 135);
    await fm.submitLimit(1, 11, "BUY", 5, 135);
  } finally {
    await done();
  }
  const ids = submitted.map((o) => o.submissionId);
  assert.ok(ids.every((id) => typeof id === "string" && id.length > 0));
  assert.notEqual(ids[0], ids[1]);
});

test("an order whose answer was lost is sent once more as the same submission", async () => {
  const { fm, submitted, done } = await serving(["lose"]);
  try {
    const placed = await fm.submitLimit(1, 11, "BUY", 5, 135);
    assert.equal(placed.id, 42);
  } finally {
    await done();
  }
  assert.equal(submitted.length, 2);
  assert.deepEqual(submitted[1], submitted[0], "the same order, the same submission");
});

test("a refused order is not sent again", async () => {
  const { fm, submitted, done } = await serving([400]);
  try {
    await assert.rejects(fm.submitLimit(1, 11, "BUY", 5, 135), InvalidArgumentError);
  } finally {
    await done();
  }
  assert.equal(submitted.length, 1);
});

test("an order lost twice is reported, and not sent a third time", async () => {
  const { fm, submitted, done } = await serving(["lose", "lose"]);
  try {
    await assert.rejects(fm.submitLimit(1, 11, "BUY", 5, 135), TypeError);
  } finally {
    await done();
  }
  assert.equal(submitted.length, 2);
});
