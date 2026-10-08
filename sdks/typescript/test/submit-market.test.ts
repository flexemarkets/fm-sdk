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

/** What the client sent after signing in: the verb and path, and any JSON body. */
interface Sent {
  request: string;
  body: Record<string, unknown> | null;
}

async function withClient(
  run: (fm: Flexemarkets, sent: Sent[]) => Promise<void>,
): Promise<void> {
  const sent: Sent[] = [];

  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const send = (payload: unknown) => {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(payload));
      };
      const url = req.url ?? "";
      if (url.startsWith("/api/tokens")) {
        send({
          token: TOKEN,
          person: { id: 7, accountId: 1, email: "dev@dev" },
          account: { id: 1, name: "dev" },
        });
        return;
      }
      sent.push({ request: `${req.method} ${url}`, body: raw ? JSON.parse(raw) : null });
      if (req.method === "GET" && url === "/api/v1/marketplaces/1/markets") {
        send(MARKETS);
      } else if (req.method === "POST" && url === "/api/v1/marketplaces/1/orders") {
        send({ id: 42, original: 42, type: "LIMIT", marketplaceId: 1, marketId: 11 });
      } else if (req.method === "DELETE" && url === "/api/v1/marketplaces/1/orders/42") {
        send({ id: 43, original: 42, consumer: 42, type: "CANCEL", marketplaceId: 1, marketId: 11 });
      } else {
        res.writeHead(404);
        res.end();
      }
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;

  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "submit-market-test");
    try {
      await run(fm, sent);
    } finally {
      await fm.close();
    }
  } finally {
    server.close();
  }
}

/** The orders submitted: the POSTs' bodies. */
const posted = (sent: Sent[]) => sent.filter((s) => s.request.startsWith("POST")).map((s) => s.body!);

test("a buy bids the highest legal price", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitMarket(1, 11, "BUY", 5);
    assert.equal(posted(sent)[0].price, 185);
    assert.equal(posted(sent)[0].type, "LIMIT");
  });
});

test("a sell offers the lowest legal price", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitMarket(1, 11, "SELL", 5);
    assert.equal(posted(sent)[0].price, 110);
  });
});

test("whatever does not fill is cancelled", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitMarket(1, 11, "BUY", 5);
    // A cancel is a DELETE of the order just placed, after it was placed.
    assert.deepEqual(sent.map((s) => s.request), [
      "GET /api/v1/marketplaces/1/markets",
      "POST /api/v1/marketplaces/1/orders",
      "DELETE /api/v1/marketplaces/1/orders/42",
    ]);
  });
});

test("an unknown market says so rather than guessing a price", async () => {
  await withClient(async (fm, sent) => {
    await assert.rejects(
      () => fm.submitMarket(1, 99, "BUY", 5),
      (e: unknown) => e instanceof InvalidArgumentError,
    );
    assert.deepEqual(sent.map((s) => s.request), ["GET /api/v1/marketplaces/1/markets"],
      "nothing was sent but the market lookup");
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

// A market order that names no side used to price at the bottom of the range -- the most aggressive sell the market accepts -- because the branch was the complement of buy rather than a test for sell. submitMarket takes the side straight from its caller, so a missing argument crossed the wrong side of the book at the worst price rather than failing.
test("a sideless market order is refused rather than priced as a sell", () => {
  assert.throws(() => marketableLimit(market(100, 200, 25), null as unknown as string),
                /must name its side/);
});
