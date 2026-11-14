/**
 * A market order: one limit order, immediate or cancel, capped at the book.
 *
 * fm-server 4.30 takes a time-in-force on a limit order (fm-server#1035), so a
 * market order is a LIMIT marked IOC at a cap from the book: the worst price
 * among others' orders it needs to fill the units. Nothing of it rests, and the
 * answer says what traded.
 *
 * Before, it was a limit at the market's extreme and then a cancel: the
 * remainder rested between the two, and a buy was cash-checked at the extreme,
 * refusing buys the trader could afford. That is still what it does against a
 * server or marketplace without IOC -- placed, then cancelled at once.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets, FlexemarketsError, InvalidArgumentError, marketCap } from "../src/client.ts";
import type { Market, Order, OrderSide } from "../src/types.ts";
import { limit } from "../src/order-utils.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

const MARKETS = [{
  id: 11, marketplaceId: 1, symbol: "STK", name: "Stock",
  priceMinimum: 110, priceMaximum: 199, priceTick: 25,
  unitMinimum: 1, unitMaximum: 100, unitTick: 1,
}];

// Sells at 1.35 (2), 1.60 (2) and 1.85 (5); the reader's own sell at 1.10; a buy
// at 1.10; and a sell in another market.
const BOOK = [
  { id: 1, type: "LIMIT", side: "SELL", units: 2, price: 135, marketId: 11, mine: false },
  { id: 2, type: "LIMIT", side: "SELL", units: 2, price: 160, marketId: 11, mine: false },
  { id: 3, type: "LIMIT", side: "SELL", units: 5, price: 185, marketId: 11, mine: false },
  { id: 4, type: "LIMIT", side: "SELL", units: 9, price: 110, marketId: 11, mine: true },
  { id: 5, type: "LIMIT", side: "BUY", units: 3, price: 110, marketId: 11, mine: false },
  { id: 6, type: "LIMIT", side: "SELL", units: 9, price: 110, marketId: 12, mine: false },
];

/** What the client sent after signing in: the verb and path, and any JSON body. */
interface Sent {
  request: string;
  body: Record<string, unknown> | null;
}

/** How the fake answers a limit: as 4.30, as a server before it, for a marketplace without IOC, or a closed session. */
type Mode = "4.30" | "old" | "no-ioc" | "closed";

async function withClient(
  run: (fm: Flexemarkets, sent: Sent[]) => Promise<void>,
  { cancelStatus = 200, mode = "4.30" as Mode, book = BOOK as unknown[] } = {},
): Promise<void> {
  const sent: Sent[] = [];

  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const send = (payload: unknown, status = 200) => {
        res.writeHead(status, { "Content-Type": "application/json" });
        res.end(JSON.stringify(payload));
      };
      const url = req.url ?? "";
      if (url.startsWith("/api/v1/tokens")) {
        send({
          token: TOKEN,
          person: { id: 7, accountId: 1, email: "dev@dev" },
          account: { id: 1, name: "dev" },
        });
        return;
      }
      const body = raw ? JSON.parse(raw) : null;
      sent.push({ request: `${req.method} ${url}`, body });
      if (req.method === "GET" && url === "/api/v1/marketplaces/1/markets") {
        send(MARKETS);
      } else if (req.method === "GET" && url === "/api/v1/marketplaces/1/orders?state=ACTIVE") {
        send(book);
      } else if (req.method === "POST" && url === "/api/v1/marketplaces/1/orders") {
        if (mode === "closed") {
          send({ error: "SESSION_CLOSED", message: "The session is closed." }, 400);
        } else if (mode === "no-ioc" && body.timeInForce === "IOC") {
          send({ error: "ORDER_INVALID", message: "This marketplace takes GTC orders, not IOC." }, 400);
        } else if (mode === "4.30") {
          const filled = Math.min(body.units, 4);
          send({ id: 42, original: 42, type: "LIMIT", marketplaceId: 1, marketId: 11, timeInForce: body.timeInForce,
                 filledUnits: filled, averagePrice: 147.5, cancelledUnits: body.units - filled });
        } else {
          send({ id: 42, original: 42, type: "LIMIT", marketplaceId: 1, marketId: 11 });
        }
      } else if (req.method === "DELETE" && url === "/api/v1/marketplaces/1/orders/42" && cancelStatus !== 200) {
        res.writeHead(cancelStatus);
        res.end();
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
const deleted = (sent: Sent[]) => sent.filter((s) => s.request.startsWith("DELETE")).map((s) => s.request);

test("a buy is one IOC limit capped at the book, and nothing is cancelled", async () => {
  await withClient(async (fm, sent) => {
    const order = await fm.submitMarket(1, 11, "BUY", 3);
    assert.deepEqual(posted(sent).map((b) => [b.type, b.price, b.timeInForce, b.units]), [["LIMIT", 160, "IOC", 3]]);
    assert.deepEqual(deleted(sent), []);
    assert.deepEqual([order.filledUnits, order.averagePrice, order.cancelledUnits], [3, 147.5, 0]);
  });
});

test("the cap is the worst price needed, and all of the side when short", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitMarket(1, 11, "BUY", 2);
    await fm.submitMarket(1, 11, "BUY", 50);
    assert.deepEqual(posted(sent).map((b) => b.price), [135, 185]);
  });
});

test("a sell caps at the buys", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitMarket(1, 11, "SELL", 1);
    assert.equal(posted(sent)[0].price, 110);
    assert.equal(posted(sent)[0].side, "SELL");
  });
});

test("an empty side is refused without sending", async () => {
  await withClient(async (fm, sent) => {
    await assert.rejects(() => fm.submitMarket(1, 11, "BUY", 1), /market 11 has no sells/);
    assert.equal(posted(sent).length, 0);
  }, { book: BOOK.filter((o) => o.side === "BUY") });
});

test("a server before time-in-force is cancelled as before", async () => {
  // It ignores timeInForce and rests what did not fill; its answer says nothing
  // of what traded, which is how this tells.
  await withClient(async (fm, sent) => {
    const order = await fm.submitMarket(1, 11, "BUY", 5);
    assert.equal(posted(sent).length, 1);
    assert.deepEqual(deleted(sent), ["DELETE /api/v1/marketplaces/1/orders/42"]);
    assert.equal(order.filledUnits, null);
  }, { mode: "old" });
});

test("a marketplace without IOC is sent a limit and a cancel", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitMarket(1, 11, "BUY", 5);
    assert.deepEqual(posted(sent).map((b) => b.timeInForce), ["IOC", undefined]);
    assert.deepEqual(deleted(sent), ["DELETE /api/v1/marketplaces/1/orders/42"]);
  }, { mode: "no-ioc" });
});

test("any other refusal is thrown and not retried as a plain limit", async () => {
  await withClient(async (fm, sent) => {
    await assert.rejects(() => fm.submitMarket(1, 11, "BUY", 5), /session is closed/);
    assert.equal(posted(sent).length, 1);
    assert.deepEqual(deleted(sent), []);
  }, { mode: "closed" });
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

test("a cancel that fails says the order was placed", async () => {
  // The limit is on the book by then. "Cancel failed" alone invites a retry of
  // the whole market order, which trades twice.
  await withClient(async (fm, sent) => {
    await assert.rejects(
      () => fm.submitMarket(1, 11, "BUY", 5),
      (e: unknown) => e instanceof FlexemarketsError
        && /Order 42 was placed/.test((e as Error).message)
        && /Do not resubmit/.test((e as Error).message),
    );
    assert.equal(posted(sent).length, 1, "submitted once");
  }, { cancelStatus: 503, mode: "old" });
});

test("a limit sends its time-in-force only when given", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitLimit(1, 11, "BUY", 5, 135);
    await fm.submitLimit(1, 11, "BUY", 5, 135, { timeInForce: "fok" });
    const [plain, fok] = posted(sent);
    assert.ok(!("timeInForce" in plain));
    assert.equal(fok.timeInForce, "FOK");
  });
});

// --- the cap itself, without a server ---------------------------------------

function order(side: string, units: number, price: number, marketId = 11, mine: boolean | null = false): Order {
  const market = { id: marketId, marketplaceId: 1, symbol: "STK" } as Market;
  return { ...limit(market, side as OrderSide, units, price), id: 1, mine };
}

test("own orders and other markets are left out", () => {
  const book = [order("SELL", 5, 100, 11, true), order("SELL", 5, 100, 12), order("SELL", 1, 150)];
  assert.equal(marketCap(book, 11, "BUY", 1), 150);
});

test("an order whose owner the server did not say counts", () => {
  assert.equal(marketCap([order("SELL", 1, 150, 11, null)], 11, "BUY", 1), 150);
});

test("side is read without regard to case", () => {
  assert.equal(marketCap([order("SELL", 1, 150)], 11, "buy", 1), 150);
});

// A market order that names no side used to price as the most aggressive sell the market accepts -- because the branch was the complement of buy rather than a test for sell. submitMarket takes the side straight from its caller, so a missing argument crossed the wrong side of the book rather than failing.
test("a sideless market order is refused rather than priced as a sell", () => {
  assert.throws(() => marketCap([order("SELL", 1, 150)], 11, null as unknown as string, 1),
                /must name its side/);
});

test("an ordinary limit order names no counterparty", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitLimit(1, 11, "BUY", 5, 135);
    const body = posted(sent)[0];
    assert.ok(!("ownerTarget" in body) && !("ownerTargetId" in body), "a null target is refused by the server");
    assert.equal(body.clientDescription, "submit-market-test");
  });
});

test("a private order names its counterparty by name or by id", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitLimit(1, 11, "SELL", 5, 135, { ownerTarget: "T2" });
    await fm.submitLimit(1, 11, "SELL", 5, 135, { ownerTargetId: 8 });
    const [byName, byId] = posted(sent);
    assert.ok(byName.ownerTarget === "T2" && !("ownerTargetId" in byName));
    assert.ok(byId.ownerTargetId === 8 && !("ownerTarget" in byId));
  });
});

test("naming the counterparty both ways is refused before sending", async () => {
  await withClient(async (fm, sent) => {
    await assert.rejects(fm.submitLimit(1, 11, "SELL", 5, 135, { ownerTarget: "T2", ownerTargetId: 8 }),
      InvalidArgumentError);
    assert.equal(posted(sent).length, 0);
  });
});

test("an order carries its own reference", async () => {
  await withClient(async (fm, sent) => {
    await fm.submitLimit(1, 11, "BUY", 5, 135, { clientDescription: "r1" });
    assert.equal(posted(sent)[0].clientDescription, "r1");
  });
});
