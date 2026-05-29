/**
 * MarketView — always-current view of a single marketplace, hiding
 * transport details (WebSocket subscribe, snapshot+delta
 * reconciliation, sequence-gap recovery, reconnect) behind a small
 * read-side surface. Mirrors the Java + Python SDKs; same staged
 * roadmap. See project_fm_sdk_canonical_client.
 *
 * Phase 1 scope: API surface + skeleton that delegates to the
 * existing listen() / callback pipe. No reconciliation, no
 * snapshot seed, no sharing, no reconnect — those land in Phase 2.
 */

import type { Flexemarkets } from "./client.js";
import { OrderBook, OrderBooks } from "./orderbook.js";
import { MarketplaceTrades } from "./trades.js";
import { NO_SEQ, type FmEvent, type OrdersUpdate } from "./stomp.js";
import type { Holding, Market, Order, Session } from "./types.js";

/**
 * Handle returned by `MarketView.on*` listener registrations. Call
 * to unregister. Idempotent — multiple calls are no-ops.
 */
export type Subscription = () => void;

export interface MarketView {
  /** The marketplace this view tracks. */
  readonly marketplaceId: number;

  /** Markets in this marketplace, captured at observe-time. */
  readonly markets: Market[];

  /**
   * Always-current order book for `marketId`. Reads are atomic; a
   * caller never sees a half-applied delta. Returns null if
   * `marketId` isn't in this marketplace.
   */
  orderBook(marketId: number): OrderBook | null;

  /**
   * Most-recent session update observed. Null until the first
   * SESSION-UPDATE frame lands.
   */
  session(): Session | null;

  /**
   * The caller's holding for this marketplace. Null until the first
   * HOLDING-UPDATE frame lands.
   */
  holding(): Holding | null;

  /** Register a handler for session-state changes. */
  onSessionChange(handler: (s: Session) => void): Subscription;

  /**
   * Register a handler that fires when the order book for
   * `marketId` changes. The handler receives the post-update book.
   */
  onOrderBookChange(marketId: number, handler: (b: OrderBook) => void): Subscription;

  /** Register a handler for the caller's holding changes. */
  onHoldingChange(handler: (h: Holding) => void): Subscription;

  /** Submit a limit order on this marketplace. */
  submitLimit(marketId: number, side: string, units: number, price: number): Promise<Order>;

  /** Cancel a previously-submitted order. */
  submitCancel(marketId: number, originalId: number): Promise<Order>;

  /**
   * Release the WS subscription and any reader-side handles. After
   * close, accessors throw and new handler registrations are
   * rejected. Idempotent.
   */
  close(): void;
}

export class DefaultMarketView implements MarketView {
  readonly marketplaceId: number;
  readonly markets: Market[];

  private readonly _flexemarkets: Flexemarkets;
  private readonly _orderBooks: OrderBooks;
  private readonly _trades: MarketplaceTrades;
  private _session: Session | null = null;
  private _holding: Holding | null = null;

  private readonly _sessionHandlers: Array<(s: Session) => void> = [];
  private readonly _holdingHandlers: Array<(h: Holding) => void> = [];
  private readonly _bookHandlers: Array<{ marketId: number; handler: (b: OrderBook) => void }> = [];

  private _closed = false;

  /**
   * Highest ORDERS-UPDATE seq this view has applied so far. Deltas
   * with `seq <= _lastAppliedSeq` are skipped — they've already been
   * folded into the local state via the initial REST snapshot or a
   * previously-applied delta. Initial value comes from the snapshot's
   * `asOfSeq`; `NO_SEQ` disables filtering (older fm-server).
   */
  private _lastAppliedSeq: number = NO_SEQ;

  static async open(flexemarkets: Flexemarkets, marketplaceId: number): Promise<DefaultMarketView> {
    const markets = await flexemarkets.markets(marketplaceId);
    const view = new DefaultMarketView(flexemarkets, marketplaceId, markets);
    // Subscribe WS first (so deltas start buffering on the callback
    // path), then fetch + apply the snapshot, only THEN allow live
    // dispatch.
    await flexemarkets.listen(marketplaceId, (event) => view._dispatch(event));
    await view._seedFromSnapshot();
    view._seedComplete = true;
    return view;
  }

  private _seedComplete = false;
  private _seedBuffer: OrdersUpdate[] = [];

  // 100 matches the default per-market Trades capacity. Plumb through to
  // observe() later if a caller needs deeper trade scrollback.
  private constructor(flexemarkets: Flexemarkets, marketplaceId: number, markets: Market[]) {
    this._flexemarkets = flexemarkets;
    this.marketplaceId = marketplaceId;
    this.markets = markets;
    this._orderBooks = new OrderBooks(markets);
    this._trades = new MarketplaceTrades(markets, 100);
  }

  /**
   * Phase 2a snapshot seeding. Fetches the V1 active-orders and
   * recent-trades snapshots, applies them to the local aggregators,
   * and records the `asOfSeq` so subsequent WS deltas can be
   * filtered to avoid double-applying anything the snapshot already
   * reflects.
   *
   * Known race window: a delta whose order persisted between the
   * server's seq capture and its order read can appear both in the
   * snapshot and in a delta with `seq > asOfSeq`, leading to a
   * double-apply. Same caveat as the existing V0 WS snapshot path.
   * Phase 2b (gap recovery + ID-based dedup) closes the window.
   */
  private async _seedFromSnapshot(): Promise<void> {
    const orders = await this._flexemarkets.activeOrdersV1(this.marketplaceId);
    const trades = await this._flexemarkets.recentTradesV1(this.marketplaceId);

    if (orders.body.length > 0) this._orderBooks.update(orders.body);
    if (trades.body.length > 0) this._trades.update(trades.body);

    this._lastAppliedSeq = orders.asOfSeq;

    // Drain anything that arrived between WS subscribe and snapshot
    // apply, filtered by seq.
    for (const buffered of this._seedBuffer) {
      this._applyOrdersUpdate(buffered);
    }
    this._seedBuffer = [];
  }

  orderBook(marketId: number): OrderBook | null {
    this._ensureOpen();
    return this._orderBooks.get(marketId) ?? null;
  }

  session(): Session | null {
    this._ensureOpen();
    return this._session;
  }

  holding(): Holding | null {
    this._ensureOpen();
    return this._holding;
  }

  onSessionChange(handler: (s: Session) => void): Subscription {
    this._ensureOpen();
    this._sessionHandlers.push(handler);
    return () => {
      const i = this._sessionHandlers.indexOf(handler);
      if (i >= 0) this._sessionHandlers.splice(i, 1);
    };
  }

  onOrderBookChange(marketId: number, handler: (b: OrderBook) => void): Subscription {
    this._ensureOpen();
    const entry = { marketId, handler };
    this._bookHandlers.push(entry);
    return () => {
      const i = this._bookHandlers.indexOf(entry);
      if (i >= 0) this._bookHandlers.splice(i, 1);
    };
  }

  onHoldingChange(handler: (h: Holding) => void): Subscription {
    this._ensureOpen();
    this._holdingHandlers.push(handler);
    return () => {
      const i = this._holdingHandlers.indexOf(handler);
      if (i >= 0) this._holdingHandlers.splice(i, 1);
    };
  }

  submitLimit(marketId: number, side: string, units: number, price: number): Promise<Order> {
    this._ensureOpen();
    return this._flexemarkets.submitLimit(this.marketplaceId, marketId, side, units, price);
  }

  submitCancel(marketId: number, originalId: number): Promise<Order> {
    this._ensureOpen();
    return this._flexemarkets.submitCancel(this.marketplaceId, marketId, originalId);
  }

  close(): void {
    if (this._closed) return;
    this._closed = true;
    // Flexemarkets is owned by the caller; we don't close it. If
    // observe() was the only consumer, the caller can close
    // Flexemarkets themselves.
  }

  private _dispatch(event: FmEvent): void {
    if (this._closed) return;
    // FmEvent is a discriminated union.
    if (_isOrdersUpdate(event)) {
      if (!this._seedComplete) {
        // Buffer until the REST snapshot has landed so we don't
        // apply deltas before the seq watermark is established.
        this._seedBuffer.push(event);
        return;
      }
      this._applyOrdersUpdate(event);
      return;
    }
    if (_isSession(event)) {
      this._session = event;
      for (const h of this._sessionHandlers) h(event);
      return;
    }
    if (_isHolding(event)) {
      this._holding = event;
      for (const h of this._holdingHandlers) h(event);
      return;
    }
    // VERSION, SESSION-LIST, WsTransportError, WsException — not
    // reflected in the public surface yet. Reconnect on transport
    // error lands in Phase 2c.
  }

  private _applyOrdersUpdate(update: OrdersUpdate): void {
    // Phase 2a seq filter: drop deltas the snapshot already reflects.
    // NO_SEQ disables filtering for older fm-server builds that don't
    // stamp the header.
    if (
      update.seq !== NO_SEQ &&
      this._lastAppliedSeq !== NO_SEQ &&
      update.seq <= this._lastAppliedSeq
    ) {
      return;
    }
    const orders = update.orders;
    const touched = _marketIdsTouched(orders);
    this._orderBooks.update(orders);
    this._trades.update(orders);
    for (const marketId of touched) {
      const book = this._orderBooks.get(marketId);
      if (!book) continue;
      for (const h of this._bookHandlers) {
        if (h.marketId === marketId) h.handler(book);
      }
    }
    if (update.seq !== NO_SEQ) this._lastAppliedSeq = update.seq;
  }

  private _ensureOpen(): void {
    if (this._closed) {
      throw new Error(`MarketView for marketplace ${this.marketplaceId} is closed`);
    }
  }
}

function _marketIdsTouched(orders: Order[]): number[] {
  const seen = new Set<number>();
  for (const o of orders) seen.add(o.marketId);
  return [...seen];
}

function _isOrdersUpdate(event: FmEvent): event is OrdersUpdate {
  return typeof event === "object" && event !== null && (event as OrdersUpdate).kind === "orders-update";
}

function _isSession(event: FmEvent): event is Session {
  return typeof event === "object" && event !== null && "status" in event && "marketplaceId" in event;
}

function _isHolding(event: FmEvent): event is Holding {
  return typeof event === "object" && event !== null && "securities" in event;
}
