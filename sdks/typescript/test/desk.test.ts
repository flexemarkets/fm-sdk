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

import { afterEach, test, mock } from "node:test";
import assert from "node:assert/strict";

import { DefaultDesk, RESEED_RETRY_MS, reseedRetry } from "../src/desk.ts";
import { parseHolding, type Flexemarkets } from "../src/client.ts";
import type { Market, Order } from "../src/types.ts";
import { parseSession } from "../src/stomp.ts";
import type { FmEvent, OrdersUpdate } from "../src/stomp.ts";
import type { Snapshot } from "../src/snapshot.ts";

const MP = 7;

afterEach(() => (reseedRetry.ms = RESEED_RETRY_MS));

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

  /** When set, the next seed's read fails with it, as a server that is down does. */
  refuseActive: Error | null = null;

  async activeOrders(): Promise<Snapshot<Order[]>> {
    this.activeReads++;
    if (this.refuseActive) throw this.refuseActive;
    return this._active;
  }

  /** The orders submitted through the desk, as `marketplace/market` and terms. */
  readonly submitted: string[] = [];

  async submitLimit(mp: number, marketId: number, side: string, units: number, price: number): Promise<Order> {
    this.submitted.push(`${mp}/${marketId} ${side} ${units}@${price}`);
    return { id: 900 } as Order;
  }

  async submitCancel(mp: number, marketId: number, originalId: number): Promise<Order> {
    this.submitted.push(`${mp}/${marketId} cancel ${originalId}`);
    return { id: 901 } as Order;
  }

  /** The trade reads made market by market, each as `marketId/size`. */
  readonly marketTradeReads: string[] = [];
  /** The trade reads made for every market at once. */
  marketplaceTradeReads = 0;

  async recentTrades(_mp: number, size = 1000, marketId?: number): Promise<Snapshot<Order[]>> {
    if (marketId === undefined) {
      this.marketplaceTradeReads++;
      return this._recent;
    }
    // One market's legs out of the recent snapshot, as the server answers ?market=.
    this.marketTradeReads.push(`${marketId}/${size}`);
    return { body: this._recent.body.filter((o) => o.marketId === marketId), asOfSeq: this._recent.asOfSeq };
  }

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

/** Both legs of one trade: `restingId` rested, `aggressorId` took it. */
function trade(m: Market, restingId: number, aggressorId: number, price: number): Order[] {
  const rested = new Date(Date.parse("2026-10-08T10:00:00Z") + restingId * 1000);
  const taken = new Date(rested.getTime() + 1_000_000);
  return [
    {
      id: restingId, original: restingId, supplier: restingId, consumer: aggressorId,
      type: "LIMIT", side: "SELL", units: 1, price, ownerId: 900, marketplaceId: MP, sessionId: 1,
      symbol: m.symbol, marketId: m.id, createdDate: rested, lastModifiedDate: taken,
    } as Order,
    {
      id: aggressorId, original: aggressorId, supplier: aggressorId, consumer: restingId,
      type: "LIMIT", side: "BUY", units: 1, price, ownerId: 901, marketplaceId: MP, sessionId: 1,
      symbol: m.symbol, marketId: m.id, createdDate: taken, lastModifiedDate: taken,
    } as Order,
  ];
}

test("each tape is seeded from its own market's read", async () => {
  // Read for every market at once, a busy market's legs filled the shared
  // limit and a quiet market's tape came up empty though it had traded
  // (fm-server#1029).
  const busy = market(1, "BUSY");
  const quiet = market(2, "QUIET");
  const legs = [...trade(busy, 101, 102, 500), ...trade(quiet, 11, 12, 700)];
  const fake = new FakeClient([busy, quiet], snapshot([], 4), snapshot(legs, 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    assert.deepEqual(fake.marketTradeReads, [
      `${busy.id}/${2 * desk.tape(busy.id)!.capacity}`,
      `${quiet.id}/${2 * desk.tape(quiet.id)!.capacity}`,
    ]);
    assert.equal(fake.marketplaceTradeReads, 0, "no read for every market at once");
    assert.deepEqual(desk.tape(quiet.id)!.mostRecentPrices(), [700]);
  } finally { desk.close(); }
});

test("a trade in the seed and again in a delta is kept once", async () => {
  // The trades snapshot is read after the orders snapshot whose sequence the
  // desk follows, so a trade made in between is in the seed and again in a
  // delta past the watermark. The tape keeps it once, and onTrade does not
  // announce it a second time.
  const alpha = market(1, "ALPHA");
  const legs = trade(alpha, 101, 102, 500);
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot(legs, 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    let announced = 0;
    desk.onTrade(alpha.id, () => { announced++; });

    fake.post(update(legs, 5));
    fake.post(update([limit(alpha, 200, "SELL", 2, 2000)], 6));   // marker
    assert.equal(desk.book(alpha.id)!.bestSellPrice(), 2000);

    assert.equal(desk.tape(alpha.id)!.size(), 1);
    assert.equal(announced, 0);
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
    assert.deepEqual(desk.tapes().map((t) => t.marketId).sort(), [alpha.id, beta.id]);
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

test("a handler that throws does not stop the desk", async () => {
  // A caller's handler is the caller's code. Gap and recovery handlers were
  // already guarded; a book, trade, session or holding handler that threw
  // escaped the stream's message callback, and left the update's seq
  // unrecorded so the next frame read as a gap.
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    desk.onBookChange(alpha.id, () => { throw new Error("a bug in the caller's handler"); });

    assert.doesNotThrow(() => fake.post(update([limit(alpha, 101, "BUY", 5, 1000)], 2)));
    fake.post(update([limit(alpha, 102, "BUY", 3, 1100)], 3));

    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1100);
    assert.equal(fake.activeReads, 1, "the second frame was not mistaken for a gap");
  } finally { desk.close(); }
});

test("a session update is the desk's session", async () => {
  // The desk recognised a session by a "status" field, and a session read off
  // the stream has "state": every SESSION-UPDATE fell through unrecognised, so
  // session() stayed null and onSessionChange never fired.
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    const seen: (string | null)[] = [];
    desk.onSessionChange((s) => seen.push(s.state));

    fake.post(parseSession({ id: 3, marketplaceId: MP, state: "OPEN" }));

    assert.equal(desk.session()?.state, "OPEN");
    assert.deepEqual(seen, ["OPEN"]);
  } finally { desk.close(); }
});

test("a holding update is the desk's holding", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    assert.equal(desk.holding(), null, "nothing has arrived yet");
    const seen: number[] = [];
    desk.onHoldingChange((h) => seen.push(h.cash));

    fake.post(parseHolding({ marketplaceId: MP, cash: 2500, securities: [{ marketId: alpha.id, units: 4 }] }));

    assert.equal(desk.holding()?.cash, 2500);
    assert.equal(desk.holding()?.securities[0]?.units, 4);
    assert.deepEqual(seen, [2500]);
  } finally { desk.close(); }
});

test("a handler that has unsubscribed hears nothing more", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    const heard: string[] = [];
    const subscriptions = [
      desk.onSessionChange(() => heard.push("session")),
      desk.onHoldingChange(() => heard.push("holding")),
      desk.onBookChange(alpha.id, () => heard.push("book")),
      desk.onTrade(alpha.id, () => heard.push("trade")),
      desk.onGap(() => heard.push("gap")),
    ];
    for (const unsubscribe of subscriptions) {
      unsubscribe();
      unsubscribe();   // idempotent
    }

    fake.post(parseSession({ id: 3, marketplaceId: MP, state: "OPEN" }));
    fake.post(parseHolding({ marketplaceId: MP, cash: 1, securities: [] }));
    fake.post(update(trade(alpha, 101, 102, 500), 2));
    assert.equal(desk.tape(alpha.id)!.size(), 1, "the trade did arrive");
    fake.nextActiveOrders(snapshot([], 9));
    fake.post(update([], 9));   // a gap
    await new Promise((r) => setTimeout(r, 50));

    assert.equal(fake.activeReads, 2, "the gap did reseed");
    assert.deepEqual(heard, []);
  } finally { desk.close(); }
});

test("a reconnect reseeds the desk and says so", async () => {
  // A reconnect is the largest gap there is: whatever happened while the
  // stream was down is only in the snapshot.
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([limit(alpha, 101, "BUY", 5, 1000)], 4),
                              snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    const recoveries: unknown[] = [];
    desk.onRecovery((e) => recoveries.push(e));

    fake.nextActiveOrders(snapshot([limit(alpha, 201, "BUY", 9, 1500)], 40));
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await new Promise((r) => setTimeout(r, 50));

    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1500);
    assert.equal(fake.activeReads, 2);
    assert.deepEqual(recoveries, [{ marketplaceId: MP, success: true, reason: null }]);
  } finally { desk.close(); }
});

test("a reconnect that cannot reseed leaves the desk stale and says why", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([limit(alpha, 101, "BUY", 5, 1000)], 4),
                              snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const error = mock.method(console, "error", () => {});
  try {
    const recoveries: unknown[] = [];
    desk.onRecovery((e) => recoveries.push(e));

    fake.refuseActive = new Error("server unavailable");
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await new Promise((r) => setTimeout(r, 50));

    assert.deepEqual(recoveries, [{ marketplaceId: MP, success: false, reason: "server unavailable" }]);
    assert.match(String(error.mock.calls[0]?.arguments[0]), /desk is stale: server unavailable/);

    // The next in sequence means nothing was missed, so it applies, as in the
    // Python and Java SDKs. One past a gap does not: see below.
    fake.refuseActive = null;
    fake.post(update([limit(alpha, 102, "BUY", 3, 1100)], 5));
    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1100);
  } finally {
    error.mock.restore();
    desk.close();
  }
});

const settled = () => new Promise((r) => setTimeout(r, 50));

test("a reseed that fails after a gap is told, and a later frame asks again", async () => {
  // The read was not awaited or caught here: its rejection went unhandled, and
  // Node's default for that ends the process.
  reseedRetry.ms = 0;
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([limit(alpha, 101, "BUY", 5, 1000)], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const error = mock.method(console, "error", () => {});
  const warn = mock.method(console, "warn", () => {});
  const unhandled: unknown[] = [];
  const record = (e: unknown) => void unhandled.push(e);
  const runnersOwn = process.listeners("unhandledRejection");
  process.removeAllListeners("unhandledRejection");
  process.on("unhandledRejection", record);
  try {
    const recoveries: unknown[] = [];
    desk.onRecovery((e) => recoveries.push(e));

    fake.refuseActive = new Error("server unavailable");
    fake.post(update([limit(alpha, 301, "BUY", 2, 3000)], 41));
    await settled();

    assert.deepEqual(unhandled, []);
    assert.deepEqual(recoveries, [{ marketplaceId: MP, success: false, reason: "server unavailable" }]);
    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1000, "not applied over the frames it missed");

    fake.refuseActive = null;
    fake.nextActiveOrders(snapshot([limit(alpha, 201, "BUY", 9, 1500)], 42));
    fake.post(update([], 42));
    await settled();

    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1500);
    assert.deepEqual(recoveries[1], { marketplaceId: MP, success: true, reason: null });
  } finally {
    process.off("unhandledRejection", record);
    for (const l of runnersOwn) process.on("unhandledRejection", l);
    error.mock.restore();
    warn.mock.restore();
    desk.close();
  }
});

test("a failed reseed is not tried again on every frame", async () => {
  reseedRetry.ms = 60_000;
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const error = mock.method(console, "error", () => {});
  const warn = mock.method(console, "warn", () => {});
  try {
    const recoveries: unknown[] = [];
    let gaps = 0;
    desk.onRecovery((e) => recoveries.push(e));
    desk.onGap(() => gaps++);
    fake.refuseActive = new Error("server unavailable");

    fake.post(update([], 41));
    await settled();
    fake.post(update([], 42));
    fake.post(update([], 43));
    await settled();

    assert.equal(fake.activeReads, 2, "one seed at open, one for the gap, none for the frames behind it");
    assert.equal(gaps, 1);
    assert.equal(recoveries.length, 1);
  } finally {
    error.mock.restore();
    warn.mock.restore();
    desk.close();
  }
});

test("a reconnect reseeds a desk a gap left stale at once", async () => {
  // It was taken for one already reseeding and ignored, so a desk once stale
  // stayed so until the caller closed it.
  reseedRetry.ms = 60_000;
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const error = mock.method(console, "error", () => {});
  const warn = mock.method(console, "warn", () => {});
  try {
    const recoveries: Array<{ success: boolean }> = [];
    desk.onRecovery((e) => recoveries.push(e));
    fake.refuseActive = new Error("server unavailable");
    fake.post(update([], 41));
    await settled();

    fake.refuseActive = null;
    fake.nextActiveOrders(snapshot([limit(alpha, 201, "BUY", 9, 1500)], 50));
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await settled();
    assert.deepEqual(recoveries.map((e) => e.success), [false, true]);

    // And it has forgotten the wait: the next gap reads straight away.
    fake.nextActiveOrders(snapshot([limit(alpha, 202, "BUY", 9, 1600)], 60));
    fake.post(update([], 60));
    await settled();
    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1600);
  } finally {
    error.mock.restore();
    warn.mock.restore();
    desk.close();
  }
});

test("a reconnect that fails twice is told twice, then a third can still succeed", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const error = mock.method(console, "error", () => {});
  try {
    const recoveries: Array<{ success: boolean }> = [];
    desk.onRecovery((e) => recoveries.push(e));
    fake.refuseActive = new Error("server unavailable");
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await settled();
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await settled();
    fake.refuseActive = null;
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await settled();

    assert.deepEqual(recoveries.map((e) => e.success), [false, false, true]);
  } finally {
    error.mock.restore();
    desk.close();
  }
});

test("a gap that heals what a failed reconnect left stale says so", async () => {
  // Told the desk is stale, a caller stops trading on it; it has to hear when that ends.
  reseedRetry.ms = 0;
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const error = mock.method(console, "error", () => {});
  const warn = mock.method(console, "warn", () => {});
  try {
    const recoveries: Array<{ success: boolean }> = [];
    desk.onRecovery((e) => recoveries.push(e));
    fake.refuseActive = new Error("server unavailable");
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await settled();

    fake.refuseActive = null;
    fake.nextActiveOrders(snapshot([], 41));
    fake.post(update([], 41));
    await settled();

    assert.deepEqual(recoveries.map((e) => e.success), [false, true]);
  } finally {
    error.mock.restore();
    warn.mock.restore();
    desk.close();
  }
});

test("an ordinary gap says nothing of recovery", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const warn = mock.method(console, "warn", () => {});
  try {
    const recoveries: unknown[] = [];
    desk.onRecovery((e) => recoveries.push(e));
    fake.nextActiveOrders(snapshot([], 41));
    fake.post(update([], 41));
    await settled();

    assert.equal(fake.activeReads, 2);
    assert.deepEqual(recoveries, []);
  } finally {
    warn.mock.restore();
    desk.close();
  }
});

test("a reconnect while one is reseeding is the same recovery", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 4), snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    let recoveries = 0;
    desk.onRecovery(() => recoveries++);

    fake.post({ kind: "reconnected", marketplaceId: MP });
    fake.post({ kind: "reconnected", marketplaceId: MP });
    await new Promise((r) => setTimeout(r, 50));

    assert.equal(fake.activeReads, 2, "one seed at open, one for the reconnect");
    assert.equal(recoveries, 1);
  } finally { desk.close(); }
});

test("a dropped stream and an unreadable frame leave the desk as it is", async () => {
  // The listener restores a dropped stream itself, and reconnecting cannot
  // mend a malformed frame: the desk says so and waits.
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([limit(alpha, 101, "BUY", 5, 1000)], 4),
                              snapshot([], 4));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  const warn = mock.method(console, "warn", () => {});
  try {
    fake.post({ kind: "stream-dropped", exception: new Error("connection reset") });
    fake.post({ kind: "frame-unreadable", command: "ERROR", headers: {}, body: "bad frame",
                exception: new Error("bad frame") });
    await new Promise((r) => setTimeout(r, 50));

    const said = warn.mock.calls.map((c) => String(c.arguments[0]));
    assert.equal(said.length, 2);
    assert.match(said[0]!, /marketplace 7: connection reset/);
    assert.match(said[1]!, /marketplace 7: ERROR bad frame/);
    assert.equal(fake.activeReads, 1, "no reseed");
    assert.equal(desk.book(alpha.id)!.bestBuyPrice(), 1000);
  } finally {
    warn.mock.restore();
    desk.close();
  }
});

test("a closed desk hears nothing from the stream", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  let heard = 0;
  desk.onBookChange(alpha.id, () => heard++);
  desk.close();

  fake.post(update([limit(alpha, 101, "BUY", 5, 1000)], 2));

  assert.equal(heard, 0);
});

test("an update for a market the desk does not keep reaches no book handler", async () => {
  const alpha = market(1, "ALPHA");
  const stranger = market(99, "STRANGER");
  const fake = new FakeClient([alpha], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    const heard: unknown[] = [];
    desk.onBookChange(stranger.id, (b) => heard.push(b));

    fake.post(update([limit(stranger, 101, "BUY", 5, 1000)], 2));

    assert.equal(desk.book(stranger.id), null);
    assert.deepEqual(heard, []);
  } finally { desk.close(); }
});

test("orders go through the desk to its own marketplace", async () => {
  const alpha = market(1, "ALPHA");
  const fake = new FakeClient([alpha], snapshot([], 1), snapshot([], 1));
  const desk = await DefaultDesk.open(asClient(fake), MP);
  try {
    assert.equal((await desk.submitLimit(alpha.id, "BUY", 2, 950)).id, 900);
    assert.equal((await desk.submitCancel(alpha.id, 900)).id, 901);
    assert.deepEqual(fake.submitted, [`${MP}/${alpha.id} BUY 2@950`, `${MP}/${alpha.id} cancel 900`]);
  } finally { desk.close(); }
});
