/**
 * A market order, on an exchange that has none.
 *
 * The server's type switch falls through to `LIMIT`, so every submission is
 * bounds-checked against the market and must sit on a tick. Java's version sent
 * `Long.MAX_VALUE` to buy and `0` to sell — prices no real market accepts — and
 * Python and TypeScript had no version at all.
 *
 * Ported once the semantics were settled: cross the book at the extreme legal
 * price, then cancel the remainder, so a market order that does not fill cannot
 * be left resting at the best price in the book.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets, InvalidArgumentError, marketableLimit } from "../src/client.ts";
import type { Market } from "../src/types.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

// priceMinimum 110, tick 25 — so the legal prices are 110, 135, 160, 185.
const MARKETS = [{
  id: 11, marketplaceId: 1, symbol: "STK", name: "Stock",
  priceMinimum: 110, priceMaximum: 199, priceTick: 25,
  unitMinimum: 1, unitMaximum: 100, unitTick: 1,
}];

async function withClient(
  run: (fm: Flexemarkets, submitted: Record<string, unknown>[]) => Promise<void>,
): Promise<void> {
  const submitted: Record<string, unknown>[] = [];

  const server = http.createServer((req, res) => {
    const send = (payload: unknown) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(payload));
    };

    if (req.method === "POST") {
      let raw = "";
      req.on("data", (c) => (raw += c));
      req.on("end", () => {
        if (req.url?.endsWith("/tokens")) {
          send({
            token: TOKEN,
            person: { id: 7, accountId: 1, email: "dev@dev" },
            account: { id: 1, name: "dev" },
          });
          return;
        }
        submitted.push(JSON.parse(raw || "{}"));
        send({ id: 42, marketplaceId: 1, marketId: 11 });
      });
      return;
    }

    const port = (req.socket.localPort ?? 0);
    if (req.url === "/api") {
      send({ _links: {
        marketplaces: { href: `http://127.0.0.1:${port}/api/marketplaces` },
        orders: { href: `http://127.0.0.1:${port}/api/orders` },
      } });
    } else if (req.url?.startsWith("/api/marketplaces/1/markets")) {
      send(MARKETS);
    } else {
      send([]);
    }
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;

  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "submit-market-test");
    try {
      await run(fm, submitted);
    } finally {
      await fm.close();
    }
  } finally {
    server.close();
  }
}

test("a buy bids the highest legal price", async () => {
  await withClient(async (fm, submitted) => {
    await fm.submitMarket(1, 11, "BUY", 5);
    assert.equal(submitted[0].price, 185);
    assert.equal(submitted[0].type, "LIMIT");
  });
});

test("a sell offers the lowest legal price", async () => {
  await withClient(async (fm, submitted) => {
    await fm.submitMarket(1, 11, "SELL", 5);
    assert.equal(submitted[0].price, 110);
  });
});

test("whatever does not fill is cancelled", async () => {
  await withClient(async (fm, submitted) => {
    await fm.submitMarket(1, 11, "BUY", 5);
    assert.equal(submitted.length, 2, "submit then cancel");
    assert.equal(submitted[1].type, "CANCEL");
    assert.equal(submitted[1].original, 42);
  });
});

test("an unknown market says so rather than guessing a price", async () => {
  await withClient(async (fm, submitted) => {
    await assert.rejects(
      () => fm.submitMarket(1, 99, "BUY", 5),
      (e: unknown) => e instanceof InvalidArgumentError,
    );
    assert.deepEqual(submitted, [], "nothing was sent");
  });
});

// --- the price rule itself, without a server --------------------------------

function market(minimum: number, maximum: number, tick: number): Market {
  return {
    id: 11, marketplaceId: 1, name: "Stock", description: null, symbol: "STK",
    privateMarket: false, priceMinimum: minimum, priceMaximum: maximum,
    priceTick: tick, unitMinimum: 1, unitMaximum: 100, unitTick: 1,
  };
}

test("the top of the range is used when it is on a tick", () => {
  assert.equal(marketableLimit(market(100, 200, 25), "BUY"), 200);
});

test("a range that is not a whole number of ticks rounds down to one", () => {
  // Anchored at priceMinimum, not zero: 110/135/160/185, so 199 -> 185.
  // Anchoring at zero would give 175, which this market refuses.
  assert.equal(marketableLimit(market(110, 199, 25), "BUY"), 185);
});

test("a fixed price market has only its floor", () => {
  assert.equal(marketableLimit(market(150, 150, 0), "BUY"), 150);
  assert.equal(marketableLimit(market(150, 150, 0), "SELL"), 150);
});

test("side is read without regard to case", () => {
  assert.equal(marketableLimit(market(100, 200, 25), "buy"), 200);
});
