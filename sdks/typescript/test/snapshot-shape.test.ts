/**
 * The snapshot reads accept a bare array and nothing else.
 *
 * `activeOrders` and `recentTrades` call `GET /api/v1/marketplaces/{id}/orders`,
 * which has only ever answered a bare array. Earlier SDKs also read HAL
 * envelopes, and answered an empty array for any shape they did not recognise
 * — which is how `Desk` once seeded empty books for months and looked
 * plausible. So any other shape is now an `ApiError`, not an empty snapshot.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { ApiError, Flexemarkets } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

const ORDER = {
  id: 80035520, original: 80035520, supplier: 80035520, consumer: null,
  type: "LIMIT", side: "BUY", symbol: "STK", units: 5, price: 125, marketId: 6560,
};

/** What the V1 route sends. */
const BARE_ARRAY = [ORDER];

/** The HAL envelope older routes sent; the V1 route never has. */
const ENVELOPE = { _embedded: { orders: [ORDER] } };

async function withClient(snapshot: unknown, run: (fm: Flexemarkets) => Promise<void>): Promise<void> {
  const server = http.createServer((req, res) => {
    const send = (payload: unknown) => {
      res.writeHead(200, { "Content-Type": "application/json", "x-fm-as-of-seq": "7" });
      res.end(JSON.stringify(payload));
    };
    if (req.url === "/api/tokens/refresh") {
      send({ token: TOKEN, person: { id: 7, accountId: 1, email: "dev@dev" },
             account: { id: 1, name: "dev" } });
    } else if (req.url === "/api/v1/marketplaces/1/orders?state=ACTIVE"
               || req.url?.startsWith("/api/v1/marketplaces/1/orders?state=TRADED&limit=")) {
      send(snapshot);
    } else {
      res.writeHead(404);
      res.end();
    }
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "snapshot-shape-test");
    try {
      await run(fm);
    } finally {
      await fm.close();
    }
  } finally {
    server.close();
  }
}

const notAList = (e: unknown) => e instanceof ApiError && /not a list of orders/.test(e.message);

test("activeOrders reads the bare array", async () => {
  await withClient(BARE_ARRAY, async (fm) => {
    const snapshot = await fm.activeOrders(1);
    assert.equal(snapshot.body.length, 1, "the order the server sent, not an empty array");
    assert.equal(snapshot.body[0].price, 125);
    assert.equal(snapshot.asOfSeq, 7);
  });
});

test("recentTrades reads the bare array", async () => {
  await withClient(BARE_ARRAY, async (fm) => {
    assert.equal((await fm.recentTrades(1)).body.length, 1);
    const snapshot = await fm.recentTrades(1, 10);
    assert.equal(snapshot.body.length, 1);
    assert.equal(snapshot.asOfSeq, 7);
  });
});

test("an envelope is an ApiError", async () => {
  await withClient(ENVELOPE, async (fm) => {
    await assert.rejects(fm.activeOrders(1), notAList);
    await assert.rejects(fm.recentTrades(1), notAList);
  });
});

for (const body of [{}, null, "orders", 42]) {
  test(`a body that is not a list is an ApiError: ${JSON.stringify(body)}`, async () => {
    await withClient(body, async (fm) => {
      await assert.rejects(fm.activeOrders(1), notAList);
    });
  });
}
