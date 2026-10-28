/**
 * A create whose answer is lost is sent again, and makes one thing.
 *
 * The order path's submissionId (submission-id.test.ts), for everything else a
 * client creates: a marketplace, a market, a user, an allocation, widgets, a
 * call. Each is sent under its own `Idempotency-Key`, and fm-server 4.17
 * answers a key it already holds with what it answered the first time (its
 * IdempotencyFilterTest), so the client can send it once more itself.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { Flexemarkets, InvalidArgumentError } from "../src/client.ts";
import type { Holding } from "../src/types.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
const CALL = {
  id: 91, marketplaceId: 1, marketId: 12, sessionId: 77, calledDate: "2026-10-25T09:00:05",
  trigger: "MANUAL", price: 550, volume: 2, demand: 3, supply: 2, buyOrders: 2, sellOrders: 1, carry: true,
};

function answerTo(path: string): unknown {
  if (path.endsWith("/calls")) return CALL;
  if (path.endsWith("/allocations") || path.endsWith("/widgets")) return [];
  if (path.endsWith("/otp/manager")) return { otps: [] };
  return { id: 5 };
}

/**
 * A client and a server that does, with each create in turn, what `plan`
 * says: "lose" cuts the connection without an answer, a number refuses with
 * that status, and anything else, or nothing, accepts. `sent` is each create
 * as its path and key, the key undefined when none was sent.
 */
async function serving(plan: Array<"lose" | number>) {
  const sent: Array<[string, string | undefined]> = [];
  const server = http.createServer((req, res) => {
    req.on("data", () => {});
    req.on("end", () => {
      const path = req.url ?? "";
      if (path.startsWith("/api/v1/tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
        return;
      }
      const key = req.headers["idempotency-key"];
      sent.push([path, typeof key === "string" ? key : undefined]);
      const step = plan.shift();
      if (step === "lose") {
        req.socket.destroy();
        return;
      }
      if (typeof step === "number") {
        res.writeHead(step, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "REQUEST_INVALID", message: "no" }));
        return;
      }
      res.writeHead(201, { "Content-Type": "application/json" });
      res.end(JSON.stringify(answerTo(path)));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "idempotency-key-test");
  const done = async () => {
    await fm.close();
    server.close();
  };
  return { fm, sent, done };
}

test("every create names its own key", async () => {
  const { fm, sent, done } = await serving([]);
  const csv = join(mkdtempSync(join(tmpdir(), "fm-idempotency-")), "holdings.csv");
  writeFileSync(csv, "name,cash\nalice,100\n");
  const grid = { minimum: 1, maximum: 100, tick: 1 };
  const holding: Holding = {
    marketplaceId: 1, sessionId: 0, allocationId: 0, ownerId: 8, name: "alice",
    cash: 10000, availableCash: 10000,
    securities: [{ marketId: 10, units: 50, availableUnits: 50, initialUnits: 50, shortUnits: 0, canBuy: true, canSell: true }],
  };
  try {
    await fm.createMarketplaceFromJson('{"name":"mp"}');
    await fm.createMarket(1, "STK", "Stock", grid, grid);
    await fm.createCallMarket(1, "CLL", "Call", grid, grid, 60, true);
    await fm.createUser("u@x", "pw", "U", "X", ["USER"]);
    await fm.allocate(1, [holding]);
    await fm.uploadHoldings(1, csv);
    await fm.uploadHoldingsCsv(1, "name,cash\nalice,100\n");
    await fm.pushWidgets(1, []);
    await fm.clearNow(1, 12);
  } finally {
    await done();
  }

  assert.deepEqual(sent.map(([path]) => path), [
    "/api/v1/marketplaces",
    "/api/v1/marketplaces/1/markets",
    "/api/v1/marketplaces/1/markets",
    "/api/v1/users",
    "/api/v1/marketplaces/1/allocations",
    "/api/v1/marketplaces/1/allocations",
    "/api/v1/marketplaces/1/allocations",
    "/api/v1/marketplaces/1/widgets",
    "/api/v1/marketplaces/1/calls",
  ]);
  const keys = sent.map(([, key]) => key);
  assert.ok(keys.every((key) => key !== undefined));
  assert.equal(new Set(keys).size, keys.length);
});

test("a post that is not a create carries no key", async () => {
  const { fm, sent, done } = await serving([]);
  try {
    await fm.managerOtpBundle([7]);
  } finally {
    await done();
  }

  assert.deepEqual(sent, [["/api/v1/otp/manager", undefined]]);
});

test("a create whose answer was lost is sent once more under the same key", async () => {
  const { fm, sent, done } = await serving(["lose"]);
  try {
    assert.equal((await fm.clearNow(1, 12)).id, 91);
  } finally {
    await done();
  }

  assert.equal(sent.length, 2);
  assert.deepEqual(sent[1], sent[0]);
});

test("a CSV create whose answer was lost is sent once more under the same key", async () => {
  const { fm, sent, done } = await serving(["lose"]);
  try {
    await fm.uploadHoldingsCsv(1, "name,cash\nalice,100\n");
  } finally {
    await done();
  }

  assert.equal(sent.length, 2);
  assert.deepEqual(sent[1], sent[0]);
});

test("a refused create is not sent again", async () => {
  const { fm, sent, done } = await serving([400]);
  try {
    await assert.rejects(fm.clearNow(1, 12), InvalidArgumentError);
  } finally {
    await done();
  }

  assert.equal(sent.length, 1);
});

test("a create lost twice is reported and not sent a third time", async () => {
  const { fm, sent, done } = await serving(["lose", "lose"]);
  try {
    await assert.rejects(fm.clearNow(1, 12));
  } finally {
    await done();
  }

  assert.equal(sent.length, 2);
});
