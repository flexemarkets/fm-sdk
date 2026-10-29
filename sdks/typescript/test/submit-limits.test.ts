/**
 * Several limit orders in one request: `POST .../orders/batch` (fm-sdk#7).
 *
 * REST one order at a time is bounded by the round trip, and several requests
 * at once reach the book out of order. A list is ordered by construction;
 * fm-server runs it in list order and answers each order as its own POST would
 * have. Here the answer is checked to come back to the caller as
 * `submitLimit`'s would: the order, or the error its refusal throws.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import {
  ApiError,
  AuthorizationError,
  ConflictError,
  Flexemarkets,
  InvalidArgumentError,
  MOST_BATCHED,
} from "../src/client.ts";
import type { Limit } from "../src/types.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

type Body = Array<Record<string, unknown>>;

/** An order's answer by its price: 999 is refused for assets, 998 already cancelled, anything else placed. */
function entry(order: Record<string, unknown>, n: number): Record<string, unknown> {
  if (order.price === 999) {
    return { status: 400, failure: { status: "BAD_REQUEST", error: "ORDER_INSUFFICIENT_ASSETS", message: "Insufficient assets." } };
  }
  if (order.price === 998) {
    return { status: 409, failure: { status: "CONFLICT", error: "ORDER_ALREADY_CANCELLED", message: "Already cancelled." } };
  }
  return { status: 201, order: { id: 1000 + n, marketplaceId: 1, marketId: order.marketId, type: "LIMIT",
                                 side: order.side, units: order.units, price: order.price,
                                 submissionId: order.submissionId } };
}

/**
 * A client and a server that does, with each batch in turn, what `plan` says:
 * "lose" cuts the connection without an answer, a number refuses the whole
 * request with that status, "short" answers one entry too few, and anything
 * else, or nothing, answers each order by its price.
 */
async function serving(plan: Array<"lose" | "short" | number>) {
  const requests: Array<{ path: string; body: Body }> = [];
  let placed = 0;
  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      if ((req.url ?? "").startsWith("/api/v1/tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
        return;
      }
      const body = JSON.parse(raw) as Body;
      requests.push({ path: req.url ?? "", body });
      const step = plan.shift();
      if (step === "lose") {
        req.socket.destroy();
        return;
      }
      if (typeof step === "number") {
        res.writeHead(step, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "FORBIDDEN", error: "ACCESS_DENIED", message: "No." }));
        return;
      }
      const answers = body.map((order) => entry(order, placed++));
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(step === "short" ? answers.slice(1) : answers));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "submit-limits-test");
  const done = async () => {
    await fm.close();
    server.close();
  };
  return { fm, requests, done };
}

const limit = (price: number, extra: Partial<Limit> = {}): Limit => ({ marketId: 11, side: "BUY", units: 1, price, ...extra });

test("the orders go in one request, in order, and come back in it", async () => {
  const { fm, requests, done } = await serving([]);
  try {
    const answered = await fm.submitLimits(1, [limit(150), limit(160, { side: "SELL" }),
                                               limit(170, { marketId: 12, clientDescription: "mine" })]);
    assert.equal(requests.length, 1);
    const { path, body } = requests[0];
    assert.equal(path, "/api/v1/marketplaces/1/orders/batch");
    assert.deepEqual(body.map((o) => o.price), [150, 160, 170]);
    assert.deepEqual(body.map((o) => o.marketId), [11, 11, 12]);
    assert.equal(body[2].clientDescription, "mine");
    assert.equal(body[0].clientDescription, "submit-limits-test");
    const ids = body.map((o) => o.submissionId);
    assert.equal(new Set(ids).size, 3, "every order names its own submission");

    const orders = await Promise.all(answered.map((p) => p.result()));
    assert.deepEqual(orders.map((o) => o.price), [150, 160, 170]);
    assert.deepEqual(answered.map((p) => p.submissionId), ids);
    assert.ok(answered.every((p) => p.done));
  } finally {
    await done();
  }
});

test("a refused order rejects with what submitLimit throws, and the rest are placed", async () => {
  const { fm, done } = await serving([]);
  try {
    const answered = await fm.submitLimits(1, [limit(150), limit(999), limit(998), limit(151)]);
    assert.equal((await answered[0].result()).price, 150);
    await assert.rejects(answered[1].result(), (e: unknown) =>
      e instanceof InvalidArgumentError && e.statusCode === 400 && e.reason === "ORDER_INSUFFICIENT_ASSETS"
        && e.message.includes("Insufficient assets."));
    await assert.rejects(answered[2].result(), (e: unknown) =>
      e instanceof ConflictError && e.reason === "ORDER_ALREADY_CANCELLED");
    assert.equal((await answered[3].result()).price, 151);
  } finally {
    await done();
  }
});

test("a long list goes as several requests, one after the other", async () => {
  const { fm, requests, done } = await serving([]);
  try {
    const limits = Array.from({ length: MOST_BATCHED * 2 + 1 }, (_, i) => limit(100 + i));
    const answered = await fm.submitLimits(1, limits);
    assert.deepEqual(requests.map((r) => r.body.length), [MOST_BATCHED, MOST_BATCHED, 1]);
    assert.deepEqual(requests.flatMap((r) => r.body.map((o) => o.price)), limits.map((l) => l.price));
    const orders = await Promise.all(answered.map((p) => p.result()));
    assert.deepEqual(orders.map((o) => o.price), limits.map((l) => l.price));
  } finally {
    await done();
  }
});

test("nothing to place sends nothing", async () => {
  const { fm, requests, done } = await serving([]);
  try {
    assert.deepEqual(await fm.submitLimits(1, []), []);
    assert.equal(requests.length, 0);
  } finally {
    await done();
  }
});

test("a request whose answer was lost is sent once more, whole", async () => {
  const { fm, requests, done } = await serving(["lose"]);
  try {
    const answered = await fm.submitLimits(1, [limit(150), limit(151)]);
    assert.equal(requests.length, 2);
    assert.deepEqual(requests[1].body, requests[0].body, "the same orders, the same submissions");
    const orders = await Promise.all(answered.map((p) => p.result()));
    assert.deepEqual(orders.map((o) => o.price), [150, 151]);
  } finally {
    await done();
  }
});

test("a refusal of the whole request is thrown, and not sent again", async () => {
  const { fm, requests, done } = await serving([403]);
  try {
    await assert.rejects(fm.submitLimits(1, [limit(150)]), AuthorizationError);
    assert.equal(requests.length, 1);
  } finally {
    await done();
  }
});

test("an answer that does not have one entry per order is an ApiError", async () => {
  const { fm, done } = await serving(["short"]);
  try {
    await assert.rejects(fm.submitLimits(1, [limit(150), limit(151)]), ApiError);
  } finally {
    await done();
  }
});

test("both counterparty names are refused before anything is sent", async () => {
  const { fm, requests, done } = await serving([]);
  try {
    await assert.rejects(fm.submitLimits(1, [limit(150), limit(150, { ownerTarget: "a", ownerTargetId: 3 })]),
                         InvalidArgumentError);
    assert.equal(requests.length, 0);
  } finally {
    await done();
  }
});
