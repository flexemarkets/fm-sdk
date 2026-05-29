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
import { NO_SEQ, type EventListener, type FmEvent, type OrdersUpdate, type WsException, type WsTransportError } from "./stomp.js";
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
  private _events: EventListener | null = null;
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
    // dispatch. _seedFromSnapshot flips _seedComplete=true atomically
    // with the buffer drain on its synchronous tail.
    //
    // Phase 2d: own the EventListener directly rather than calling
    // flexemarkets.listen() — that would clobber the singleton
    // _eventListener and prevent multiple shared views from
    // coexisting in one Flexemarkets.
    view._events = await flexemarkets._connectEvents(marketplaceId, (event) => view._dispatch(event));
    await view._seedFromSnapshot();
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

    // Clear before reseeding so a resync (Phase 2b) doesn't double-add
    // against existing price levels. Initial seed hits empty books so
    // clear() is a no-op there.
    this._orderBooks.clear();
    this._trades.clear();

    if (orders.body.length > 0) this._orderBooks.update(orders.body);
    if (trades.body.length > 0) this._trades.update(trades.body);

    this._lastAppliedSeq = orders.asOfSeq;

    // Flip state and drain in a single synchronous block — no
    // microtask boundary, so no concurrent dispatch can squeeze in
    // between "buffer drained" and "_seedComplete=true" and leave its
    // delta orphaned in the buffer.
    const buffered = this._seedBuffer;
    this._seedBuffer = [];
    this._seedComplete = true;
    this._resyncInFlight = false;
    for (const update of buffered) this._applyOrdersUpdate(update);
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
    if (this._events !== null) {
      try { this._events.close(); } catch { /* best-effort */ }
      this._events = null;
    }
    // Flexemarkets is owned by the caller; we don't close it. If
    // observe() was the only consumer, the caller can close
    // Flexemarkets themselves.
  }

  private _resyncInFlight = false;

  private _dispatch(event: FmEvent): void {
    if (this._closed) return;
    // FmEvent is a discriminated union.
    if (_isOrdersUpdate(event)) {
      if (!this._seedComplete || this._resyncInFlight) {
        // Buffer until the REST snapshot has landed so we don't
        // apply deltas before the seq watermark is established.
        this._seedBuffer.push(event);
        return;
      }
      // Phase 2b gap detection: a delta with seq > lastAppliedSeq+1
      // means one or more frames were dropped. Refetch the snapshot
      // and let the seq filter skip whatever the new asOfSeq covers.
      if (
        event.seq !== NO_SEQ &&
        this._lastAppliedSeq !== NO_SEQ &&
        event.seq > this._lastAppliedSeq + 1
      ) {
        console.warn(
          `[MarketView] ORDERS-UPDATE seq gap on marketplace ${this.marketplaceId} ` +
            `— expected ${this._lastAppliedSeq + 1}, got ${event.seq}; resyncing from snapshot`,
        );
        this._resyncInFlight = true;
        this._seedComplete = false;
        this._seedBuffer.push(event);
        // _seedFromSnapshot flips both flags back atomically on its
        // synchronous tail; nothing more to do in the .then().
        void this._seedFromSnapshot();
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
    if (_isTransportError(event)) {
      this._handleTransportError();
      return;
    }
    if (_isWsException(event)) {
      // STOMP ERROR / parse failure. Logged for visibility;
      // reconnecting won't help with a malformed frame, so we leave
      // the view as-is.
      console.warn(
        `[MarketView] WS error on marketplace ${this.marketplaceId}: ${event.command} ${event.body}`,
      );
      return;
    }
    // VERSION, SESSION-LIST — not reflected in the public surface yet.
  }

  /**
   * Phase 2c auto-reconnect. On a WsTransportError, reconnect the
   * underlying WS and re-seed from the V1 snapshot — reconnect is
   * just the largest possible gap, so 2b's _seedFromSnapshot()
   * (clear + REST snapshot + reapply + seq watermark) handles state
   * convergence. One reconnect attempt; if it fails the view is left
   * stale until the caller close()s and observe()s again.
   */
  private _handleTransportError(): void {
    if (this._resyncInFlight) return; // already handling
    console.warn(
      `[MarketView] WS transport error on marketplace ${this.marketplaceId}; reconnecting`,
    );
    this._resyncInFlight = true;
    this._seedComplete = false;
    void (async () => {
      try {
        if (this._events !== null) await this._events.reconnect();
        await this._seedFromSnapshot();
      } catch (err) {
        console.error(
          `[MarketView] Reconnect failed on marketplace ${this.marketplaceId}; view is stale: ` +
            (err instanceof Error ? err.message : String(err)),
        );
        // Leave _resyncInFlight=true so subsequent dispatches keep
        // buffering rather than corrupting state; caller can close()
        // and observe() to recover.
      }
    })();
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

function _isTransportError(event: FmEvent): event is WsTransportError {
  return typeof event === "object" && event !== null && (event as WsTransportError).kind === "transport-error";
}

function _isWsException(event: FmEvent): event is WsException {
  return typeof event === "object" && event !== null && (event as WsException).kind === "ws-exception";
}

/**
 * Reader-side handle on a refcounted DefaultMarketView. Returned by
 * Flexemarkets.observe(); multiple handles for the same marketplaceId
 * share one underlying view + WS subscription + materialized state.
 * Each handle's close() decrements the shared refcount and tears down
 * the shared resources on the last close.
 *
 * Subscriptions registered via on*Change on this handle are tracked
 * locally and closed when the handle closes — so a handler doesn't
 * keep firing into stale state after the handle is gone.
 */
export class MarketViewHandle implements MarketView {
  private readonly _shared: DefaultMarketView;
  private readonly _onClose: () => void;
  private readonly _mySubscriptions: Subscription[] = [];
  private _closed = false;

  constructor(shared: DefaultMarketView, onClose: () => void) {
    this._shared = shared;
    this._onClose = onClose;
  }

  get marketplaceId(): number {
    return this._shared.marketplaceId;
  }

  get markets(): Market[] {
    this._check();
    return this._shared.markets;
  }

  orderBook(marketId: number): OrderBook | null {
    this._check();
    return this._shared.orderBook(marketId);
  }

  session(): Session | null {
    this._check();
    return this._shared.session();
  }

  holding(): Holding | null {
    this._check();
    return this._shared.holding();
  }

  onSessionChange(handler: (s: Session) => void): Subscription {
    this._check();
    const sub = this._shared.onSessionChange(handler);
    this._mySubscriptions.push(sub);
    return sub;
  }

  onOrderBookChange(marketId: number, handler: (b: OrderBook) => void): Subscription {
    this._check();
    const sub = this._shared.onOrderBookChange(marketId, handler);
    this._mySubscriptions.push(sub);
    return sub;
  }

  onHoldingChange(handler: (h: Holding) => void): Subscription {
    this._check();
    const sub = this._shared.onHoldingChange(handler);
    this._mySubscriptions.push(sub);
    return sub;
  }

  submitLimit(marketId: number, side: string, units: number, price: number): Promise<Order> {
    this._check();
    return this._shared.submitLimit(marketId, side, units, price);
  }

  submitCancel(marketId: number, originalId: number): Promise<Order> {
    this._check();
    return this._shared.submitCancel(marketId, originalId);
  }

  close(): void {
    if (this._closed) return;
    this._closed = true;
    // Close subscriptions this handle registered so handlers don't
    // fire into a closed handle. Subscription is a () => void; calling
    // twice is idempotent per the existing TS Subscription contract.
    for (const sub of this._mySubscriptions) {
      try { sub(); } catch { /* best-effort */ }
    }
    this._mySubscriptions.length = 0;
    this._onClose();
  }

  private _check(): void {
    if (this._closed) {
      throw new Error(`MarketView handle for marketplace ${this._shared.marketplaceId} is closed`);
    }
  }
}
