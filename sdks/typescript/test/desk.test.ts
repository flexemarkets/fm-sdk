/**
 * The desk, driven by a scripted stream rather than a live server.
 *
 * Mirrors the Java DeskTest and the Python test_desk case for case. All three
 * had desk coverage only around the edges -- reconnect bookkeeping,
 * subscription sharing -- and nothing that drove one through seed, delta,
 * sequence filtering and gap recovery, which is where the three SDKs have
 * actually been wrong together.
 *
 * These assert the book's *contents*, not a log line.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { DefaultDesk } from "../src/desk.ts";
import type { Flexemarkets } from "../src/client.ts";
import type { Market, Order } from "../src/types.ts";
import type { FmEvent, OrdersUpdate } from "../src/stomp.ts";
import type { Snapshot } from "../src/snapshot.ts";

const MP = 7;

function market(id: number, symbol: string): Market {
  return {
    id, marketplaceId: MP, name: symbol, description: symbol, symbol,
    privateMarket: false, priceMinimum: 0, priceMaximum: 10_000, priceTick: 1,
    unitMinimum: 1, unitMaximum: 100, unitTick: 1,
  } as Market;
}

function limit(m: Market, id: number, side: string, units: number, price: number): Order {
  return {
    id, original: id, supplier: id, consumer: null, type: "LIMIT", side,
    units, price, marketplaceId: MP, sessionId: 1, symbol: m.symbol, marketId: m.id,
  } as Order;
}

/** Answers the three calls a desk makes, and hands the test the dispatch hook. */
class FakeClient {
  activeReads = 0;
  private _dispatch: ((e: FmEvent) => void) | null = null;

  private readonly _markets: Market[];
  private _active: Snapshot<Order[]>;
  private readonly _recent: Snapshot<Order[]>;

  constructor(markets: Market[], active: Snapshot<Order[]>, recent: Snapshot<Order[]>) {
    this._markets = markets;
    this._active = active;
    this._recent = recent;
  }

  /** What the next seed reads, so a reseed can differ from the first. */
  nextActiveOrders(next: Snapshot<Order[]>): void { this._active = next; }

  post(event: FmEvent): void {
    if (!this._dispatch) throw new Error("nothing has subscribed yet");
    this._dispatch(event);
  }

  async markets(): Promise<Market[]> { return this._markets; }

  async activeOrders(): Promise<Snapshot<Order[]>> {
    this.activeReads++;
    return this._active;
  }

  async recentTrades(): Promise<Snapshot<Order[]>> { return this._recent; }

  async _connectEvents(_mp: number, dispatch: (e: FmEvent) => void): Promise<unknown> {
    this._dispatch = dispatch;
    return { close: () => {} };
  }
}

const snapshot = (body: Order[], asOfSeq: number): Snapshot<Order[]> => ({ body, asOfSeq });
const update = (orders: Order[], seq: number): OrdersUpdate =>
  ({ kind: "orders-update", orders, seq });

const asClient = (f: FakeClient) => f as unknown as Flexemarkets;

test("a desk seeds its books from the snapshot", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([limit(alpha, 101, "BUY", 5, 1000)], 4),
                              snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1000);
    assert.equal(desk.book(alpha.id)!.bestBuyUnits(), 5);
  } finally { desk.close(); }
});

test("a delta after the seed is applied", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([limit(alpha, 101, "BUY", 5, 1000)], 4),
                              snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    fake.post(update([limit(alpha, 102, "BUY", 3, 1100)], 5));
    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1100);
    assert.equal(desk.book(alpha.id)!.bestBuyUnits(), 3);
  } finally { desk.close(); }
});

test("a delta already in the seed is not applied twice", async () => {
  // A book aggregates by price level, not by order id, so applying one twice
  // adds its units twice and the book reads deeper than the market is.
  const alpha = market(1, "ALPHA");
  const resting = limit(alpha, 101, "BUY", 5, 1000);
  const fake = new FakeClient([alpha], snapshot([resting], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    fake.post(update([resting], 4));                              // already in the seed
    fake.post(update([limit(alpha, 102, "SELL", 2, 2000)], 5));   // marker

    assert.equal(desk.book(alpha.id)!.bestSellPrice(), 2000);
    assert.equal(desk.book(alpha.id)!.bestBuyUnits(), 5,
                 "re-delivered seed order counted twice");
  } finally { desk.close(); }
});

test("books and tapes cover every market", async () => {
  const alpha = market(1, "ALPHA");
  const beta = market(2, "BETA");
  const fake = new FakeClient([alpha, beta], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    assert.equal(desk.books().length, 2);
    assert.equal(desk.tapes().length, 2);
    assert.deepEqual(desk.books().map((b) => b.marketId).sort(), [alpha.id, beta.id]);
  } finally { desk.close(); }
});

test("a gap reseeds the book from the snapshot and says so", async () => {
  // A gap must leave the book *right*, not merely leave a message. The reseed
  // answers a different book, so a resync that kept the stale one fails here.
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([limit(alpha, 101, "BUY", 5, 1000)], 4),
                              snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    let gaps = 0;
    desk.onGap(() => gaps++);

    fake.nextActiveOrders(snapshot([limit(alpha, 201, "BUY", 9, 1500)], 40));
    fake.post(update([], 41));   // seq 41 with last-applied 4 is a gap of 36

    await new Promise((r) => setTimeout(r, 50));

    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1500);
    assert.equal(desk.book(alpha.id)!.bestBuyUnits(), 9);
    assert.equal(gaps, 1, "onGap fired");
    assert.equal(fake.activeReads, 2, "one seed at open, one at the gap");
  } finally { desk.close(); }
});

test("consecutive frames are not a gap", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    let gaps = 0;
    desk.onGap(() => gaps++);

    fake.post(update([limit(alpha, 102, "BUY", 1, 900)], 5));
    fake.post(update([limit(alpha, 103, "BUY", 1, 950)], 6));

    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 950);
    assert.equal(gaps, 0);
    assert.equal(fake.activeReads, 1, "no reseed");
  } finally { desk.close(); }
});
