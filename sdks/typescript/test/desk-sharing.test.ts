/**
 * `Flexemarkets.desk(id)`, the call every caller makes: one desk and one
 * stream per marketplace however many handles ask, torn down when the last
 * handle closes. Mirrors the Java DeskSharingTest and the Python
 * test_desk_sharing case for case, plus one only TypeScript needs: two
 * desk() calls in flight at once.
 *
 * desk.test.ts drives a DefaultDesk directly, so until this the sharing and
 * the DeskHandle every caller actually holds ran only in the live-server
 * test, which skips without a server. Here the client is real; only the calls
 * a desk makes to it answer from the test.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { Flexemarkets, parseHolding } from "../src/client.ts";
import { parseSession } from "../src/stomp.ts";
import type { Desk } from "../src/desk.ts";
import type { Market, Order } from "../src/types.ts";
import type { FmEvent, OrdersUpdate } from "../src/stomp.ts";
import type { Snapshot } from "../src/snapshot.ts";

const MP = 1;
const ALPHA = 11;

function market(marketplaceId: number): Market {
  return {
    id: ALPHA, marketplaceId, name: "ALPHA", description: "ALPHA", symbol: "ALPHA",
    privateMarket: false, priceMinimum: 0, priceMaximum: 10_000, priceTick: 1,
    unitMinimum: 1, unitMaximum: 100, unitTick: 1,
  } as Market;
}

function limit(marketplaceId: number, id: number, side: string, units: number, price: number): Order {
  return {
    id, original: id, supplier: id, consumer: null, type: "LIMIT", side,
    units, price, marketplaceId, sessionId: 1, symbol: "ALPHA", marketId: ALPHA,
  } as Order;
}

const update = (orders: Order[], seq: number): OrdersUpdate =>
  ({ kind: "orders-update", orders, seq });

/** The real client, with the calls a desk makes answering from the test. */
interface Scripted extends Flexemarkets {
  activeReads: number;
  unsubscribes: number;
  subscribed: number[];
  submitted: string[];
  post(marketplaceId: number, event: FmEvent): void;
}

function scripted(): Scripted {
  // The constructor is private to keep callers on connect(), which signs in.
  const Ctor = Flexemarkets as unknown as new (...args: string[]) => Flexemarkets;
  const fm = new Ctor(`http://127.0.0.1:1/api/marketplaces/${MP}`, "http://127.0.0.1:1/api", "Bearer t", "desk-sharing-test") as Scripted;
  const streams = new Map<number, (e: FmEvent) => void>();
  Object.assign(fm, {
    activeReads: 0,
    unsubscribes: 0,
    subscribed: [] as number[],
    submitted: [] as string[],
    post(marketplaceId: number, event: FmEvent) { streams.get(marketplaceId)!(event); },
    async markets(marketplaceId: number) { return [market(marketplaceId)]; },
    async activeOrders(marketplaceId: number): Promise<Snapshot<Order[]>> {
      fm.activeReads++;
      return { body: [limit(marketplaceId, 101, "BUY", 5, 1000)], asOfSeq: 4 };
    },
    async recentTrades(): Promise<Snapshot<Order[]>> { return { body: [], asOfSeq: 4 }; },
    async _connectEvents(marketplaceId: number, dispatch: (e: FmEvent) => void) {
      fm.subscribed.push(marketplaceId);
      streams.set(marketplaceId, dispatch);
      return { close: () => { fm.unsubscribes++; } };
    },
    async submitLimit(marketplaceId: number, marketId: number, side: string, units: number, price: number) {
      fm.submitted.push(`${marketplaceId}/${marketId} ${side} ${units}@${price}`);
      return limit(marketplaceId, 900, side, units, price);
    },
    async submitCancel(marketplaceId: number, marketId: number, originalId: number) {
      fm.submitted.push(`${marketplaceId}/${marketId} cancel ${originalId}`);
      return limit(marketplaceId, 901, "BUY", 0, 0);
    },
  });
  return fm;
}

test("two handles share one desk, one seed and one stream", async () => {
  const fm = scripted();
  const a = await fm.desk(MP);
  const b = await fm.desk(MP);

  assert.deepEqual(fm.subscribed, [MP]);
  assert.equal(fm.activeReads, 1);

  // One state behind both: a delta through the one stream reaches each.
  fm.post(MP, update([limit(MP, 102, "BUY", 3, 1100)], 5));
  assert.equal(a.book(ALPHA)!.bestBuyPrice(), 1100);
  assert.equal(b.book(ALPHA)!.bestBuyPrice(), 1100);

  a.close();
  b.close();
  fm.close();
});

test("two desk() calls in flight at once still share one desk", async () => {
  const fm = scripted();
  const [a, b] = await Promise.all([fm.desk(MP), fm.desk(MP)]);

  assert.deepEqual(fm.subscribed, [MP]);
  a.close();
  assert.equal(fm.unsubscribes, 0);
  b.close();
  assert.equal(fm.unsubscribes, 1);
  fm.close();
});

test("the stream closes when the last handle does and not before", async () => {
  const fm = scripted();
  const a = await fm.desk(MP);
  const b = await fm.desk(MP);

  a.close();
  assert.equal(fm.unsubscribes, 0, "closed with a handle still open");
  assert.equal(b.book(ALPHA)!.bestBuyPrice(), 1000);

  b.close();
  assert.equal(fm.unsubscribes, 1);
  fm.close();
});

test("closing a handle twice releases it once", async () => {
  const fm = scripted();
  const a = await fm.desk(MP);
  const b = await fm.desk(MP);

  a.close();
  a.close();

  assert.equal(fm.unsubscribes, 0);
  assert.notEqual(b.book(ALPHA), null);
  b.close();
  fm.close();
});

test("a desk opened after the last close is a new one", async () => {
  const fm = scripted();
  (await fm.desk(MP)).close();
  const again = await fm.desk(MP);

  assert.deepEqual(fm.subscribed, [MP, MP]);
  assert.equal(fm.activeReads, 2);
  assert.equal(again.book(ALPHA)!.bestBuyPrice(), 1000);
  again.close();
  fm.close();
});

test("each marketplace has its own desk", async () => {
  const fm = scripted();
  const one = await fm.desk(1);
  const two = await fm.desk(2);

  assert.deepEqual([...fm.subscribed].sort(), [1, 2]);
  assert.equal(one.marketplaceId, 1);
  assert.equal(two.marketplaceId, 2);
  one.close();
  two.close();
  fm.close();
});

test("a closed handle refuses use while the others carry on", async () => {
  const fm = scripted();
  const a = await fm.desk(MP);
  const b = await fm.desk(MP);
  a.close();

  assert.throws(() => a.book(ALPHA), /closed/);
  assert.throws(() => a.markets, /closed/);
  assert.throws(() => a.onBookChange(ALPHA, () => {}), /closed/);
  assert.throws(() => a.submitLimit(ALPHA, "BUY", 1, 1000), /closed/);
  assert.deepEqual(b.markets.map((m) => m.id), [ALPHA]);
  b.close();
  fm.close();
});

test("a handle's handlers stop when it closes; the other handle's keep firing", async () => {
  const fm = scripted();
  const a = await fm.desk(MP);
  const b = await fm.desk(MP);
  const seenByA: number[] = [];
  const seenByB: number[] = [];
  a.onBookChange(ALPHA, (book) => seenByA.push(book.bestBuyPrice()));
  b.onBookChange(ALPHA, (book) => seenByB.push(book.bestBuyPrice()));

  a.close();
  fm.post(MP, update([limit(MP, 102, "BUY", 3, 1100)], 5));

  assert.deepEqual(seenByB, [1100]);
  assert.deepEqual(seenByA, []);
  b.close();
  fm.close();
});

test("a handle reads and trades through the shared desk", async () => {
  const fm = scripted();
  const desk = await fm.desk(MP);

  assert.equal(desk.book(ALPHA)!.bestBuyPrice(), 1000);
  assert.equal(desk.books().length, 1);
  assert.equal(desk.tapes().length, 1);
  assert.notEqual(desk.tape(ALPHA), null);

  await desk.submitLimit(ALPHA, "SELL", 2, 1500);
  await desk.submitCancel(ALPHA, 900);
  assert.deepEqual(fm.submitted, [`${MP}/${ALPHA} SELL 2@1500`, `${MP}/${ALPHA} cancel 900`]);
  desk.close();
  fm.close();
});

test("closing the client closes the desks its callers left open", async () => {
  const fm = scripted();
  await fm.desk(MP);
  await fm.desk(2);

  fm.close();

  assert.equal(fm.unsubscribes, 2);
});

/** Registers a handler of every kind on `desk`, each recording its kind. */
function listenToEverything(desk: Desk): string[] {
  const heard: string[] = [];
  desk.onSessionChange(() => heard.push("session"));
  desk.onHoldingChange(() => heard.push("holding"));
  desk.onBookChange(ALPHA, () => heard.push("book"));
  desk.onTrade(ALPHA, () => heard.push("trade"));
  desk.onGap(() => heard.push("gap"));
  desk.onRecovery(() => heard.push("recovery"));
  return heard;
}

/** One event of every kind a handler can hear, through the one stream. */
async function postEverything(fm: Scripted): Promise<void> {
  const at = new Date("2026-10-08T10:00:00Z");
  fm.post(MP, parseSession({ id: 3, marketplaceId: MP, state: "OPEN" }));
  fm.post(MP, parseHolding({ marketplaceId: MP, cash: 2500, securities: [] }));
  fm.post(MP, update([
    { ...limit(MP, 102, "SELL", 1, 1000), consumer: 103, lastModifiedDate: at },
    { ...limit(MP, 103, "BUY", 1, 1000), consumer: 102, lastModifiedDate: at },
  ], 5));
  fm.post(MP, update([], 9));   // a gap
  await new Promise((r) => setTimeout(r, 50));
  fm.post(MP, { kind: "reconnected", marketplaceId: MP });
  await new Promise((r) => setTimeout(r, 50));
}

test("a handle hears every kind of event, and reads what they carried", async () => {
  const fm = scripted();
  const desk = await fm.desk(MP);
  const heard = listenToEverything(desk);

  await postEverything(fm);

  assert.deepEqual(heard, ["session", "holding", "trade", "book", "gap", "recovery"]);
  assert.equal(desk.session()?.state, "OPEN");
  assert.equal(desk.holding()?.cash, 2500);
  desk.close();
  fm.close();
});

test("a closed handle's handlers of every kind stop", async () => {
  const fm = scripted();
  const a = await fm.desk(MP);
  const b = await fm.desk(MP);
  const heardByA = listenToEverything(a);
  const heardByB = listenToEverything(b);
  a.close();

  await postEverything(fm);

  assert.deepEqual(heardByA, []);
  assert.equal(heardByB.length, 6, heardByB.join(", "));
  assert.throws(() => a.session(), /closed/);
  assert.throws(() => a.holding(), /closed/);
  b.close();
  fm.close();
});
