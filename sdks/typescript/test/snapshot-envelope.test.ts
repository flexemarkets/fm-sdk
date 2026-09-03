/**
 * The snapshot routes return orders inside a HAL envelope, under `orders`.
 *
 * Every SDK read `_embedded.orderDtoes` — Spring HATEOAS pluralising the
 * server's old `OrderDto` — long after the server started sending
 * `_embedded.orders`. So `activeOrders` and `recentTrades` returned an empty
 * array always, in all three SDKs, for their whole life.
 *
 * Not cosmetic: `Desk` seeds its books from `activeOrders`, so the seed
 * was always empty and the books filled from live deltas afterwards — which
 * looks plausible until you open a desk on a marketplace that already has
 * resting orders and see nothing.
 *
 * Nothing caught it because nothing tested it.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

/** Sampled from a running fm-server. */
const ENVELOPE = {
  _embedded: {
    orders: [{
      id: 80035520, original: 80035520, supplier: 80035520, consumer: null,
      type: "LIMIT", side: "BUY", symbol: "STK", units: 5, price: 125, marketId: 6560,
    }],
  },
};

async function withClient(run: (fm: Flexemarkets) => Promise<void>): Promise<void> {
  const server = http.createServer((req, res) => {
    const send = (payload: unknown) => {
      res.writeHead(200, { "Content-Type": "application/json", "x-fm-as-of-seq": "7" });
      res.end(JSON.stringify(payload));
    };
    if (req.url === "/api/tokens/refresh") {
      send({ token: TOKEN, person: { id: 7, accountId: 1, email: "dev@dev" },
             account: { id: 1, name: "dev" } });
    } else if (req.url?.includes("/orders/active") || req.url?.includes("/orders/recent-trades")) {
      send(ENVELOPE);
    } else if (req.url === "/api") {
      send({ _links: {} });
    } else {
      send([]);
    }
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "envelope-test");
    try {
      await run(fm);
    } finally {
      await fm.close();
    }
  } finally {
    server.close();
  }
}

test("activeOrders reads the envelope", async () => {
  await withClient(async (fm) => {
    const snapshot = await fm.activeOrders(1);
    assert.equal(snapshot.body.length, 1, "the order the server sent, not an empty array");
    assert.equal(snapshot.body[0].price, 125);
    assert.equal(snapshot.asOfSeq, 7);
  });
});

test("recentTrades reads the envelope", async () => {
  await withClient(async (fm) => {
    assert.equal((await fm.recentTrades(1)).body.length, 1);
    assert.equal((await fm.recentTrades(1, 10)).body.length, 1);
  });
});
