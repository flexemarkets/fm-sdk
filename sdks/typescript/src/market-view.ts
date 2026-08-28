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
import { MarketBook, OrderBooks } from "./orderbook.js";
import { MarketplaceTrades, type Trade, type Trades } from "./trades.js";
import { NO_SEQ, type EventListener, type FmEvent, type OrdersUpdate, type FrameUnreadable, type Reconnected, type StreamDropped } from "./stomp.js";
import type { Holding, Market, Order, Session } from "./types.js";

/**
 * Handle returned by `MarketView.on*` listener registrations. Call
 * to unregister. Idempotent — multiple calls are no-ops.
 */
export type Subscription = () => void;

/**
 * Fires when MarketView detects a sequence-gap in the ORDERS-UPDATE
 * WS stream — one or more frames were dropped between client and
 * fm-server. After a gap, MarketView re-runs the REST snapshot
 * recovery flow before applying further deltas; the gap event is the
 * signal callers can wire into their own observability stack instead
 * of relying on the SDK's default console.warn.
 */
export interface GapEvent {
  readonly marketplaceId: number;
  readonly expectedSeq: number;
  readonly receivedSeq: number;
}

/**
 * Fires when MarketView reacts to a StreamDropped — either after
 * the reconnect + resnapshot completes successfully, or after the
 * attempt has failed and the view is left stale.
 */
export interface ReconnectEvent {
  readonly marketplaceId: number;
  readonly success: boolean;
  readonly reason: string | null;
}

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
  orderBook(marketId: number): MarketBook | null;

  /**
   * Always-current trade tape for `marketId`, most recent last. Returns null
   * if `marketId` isn't in this marketplace.
   *
   * Maintained alongside {@link orderBook}: seeded from the server's recent
   * trades when the view opens, then kept current from the same delta stream.
   * Each `Trade` on it carries both sides of its match — the resting
   * order that was taken and the aggressor that took it.
   */
  trades(marketId: number): Trades | null;

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
  onOrderBookChange(marketId: number, handler: (b: MarketBook) => void): Subscription;

  /**
   * Register a handler that fires for each trade on `marketId`.
   *
   * The missing member of the family {@link onOrderBookChange} and
   * {@link onSessionChange} belong to. Without it, "tell me when a trade
   * happens" is asked as "tell me when the book changed, then let me look" —
   * which answers a different question, since a book changes on every resting
   * order and most changes are not trades.
   *
   * Fires **once per trade**, oldest first, rather than coalescing a batch the
   * way {@link onOrderBookChange} does. A trade is a discrete event with its own
   * price and counterparties; collapsing two into one callback would lose one.
   *
   * Live deltas only. A gap or a reconnect re-seeds the tape from the server's
   * snapshot, and those trades do not fire here — they are not new, they are
   * what was missed. {@link onGap} and {@link onReconnect} are how a caller
   * learns that happened.
   */
  onTrade(marketId: number, handler: (t: Trade) => void): Subscription;

  /** Register a handler for the caller's holding changes. */
  onHoldingChange(handler: (h: Holding) => void): Subscription;

  /**
   * Register a handler that fires when MarketView detects a gap in
   * the ORDERS-UPDATE seq stream. Use this to wire your own
   * telemetry — by default the SDK logs to console.warn but
   * otherwise hides the recovery flow.
   */
  onGap(handler: (event: GapEvent) => void): Subscription;

  /**
   * Register a handler that fires after the SDK reacts to a
   * transport error — either when reconnect + resnapshot have
   * completed, or when the attempt has failed and the view is left
   * stale.
   */
  onReconnect(handler: (event: ReconnectEvent) => void): Subscription;

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
  private readonly _bookHandlers: Array<{ marketId: number; handler: (b: MarketBook) => void }> = [];
  private readonly _tradeHandlers: Array<{ marketId: number; handler: (t: Trade) => void }> = [];
  private readonly _gapHandlers: Array<(e: GapEvent) => void> = [];
  private readonly _reconnectHandlers: Array<(e: ReconnectEvent) => void> = [];

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
    const orders = await this._flexemarkets.activeOrders(this.marketplaceId);
    const trades = await this._flexemarkets.recentTrades(this.marketplaceId);

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

  orderBook(marketId: number): MarketBook | null {
    this._ensureOpen();
    return this._orderBooks.get(marketId) ?? null;
  }

  trades(marketId: number): Trades | null {
    this._ensureOpen();
    return this._trades.get(marketId);
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

  onTrade(marketId: number, handler: (t: Trade) => void): Subscription {
    this._ensureOpen();
    const entry = { marketId, handler };
    this._tradeHandlers.push(entry);
    return () => {
      const i = this._tradeHandlers.indexOf(entry);
      if (i >= 0) this._tradeHandlers.splice(i, 1);
    };
  }

  onOrderBookChange(marketId: number, handler: (b: MarketBook) => void): Subscription {
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

  onGap(handler: (e: GapEvent) => void): Subscription {
    this._ensureOpen();
    this._gapHandlers.push(handler);
    return () => {
      const i = this._gapHandlers.indexOf(handler);
      if (i >= 0) this._gapHandlers.splice(i, 1);
    };
  }

  onReconnect(handler: (e: ReconnectEvent) => void): Subscription {
    this._ensureOpen();
    this._reconnectHandlers.push(handler);
    return () => {
      const i = this._reconnectHandlers.indexOf(handler);
      if (i >= 0) this._reconnectHandlers.splice(i, 1);
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
        const expectedSeq = this._lastAppliedSeq + 1;
        console.warn(
          `[MarketView] ORDERS-UPDATE seq gap on marketplace ${this.marketplaceId} ` +
            `— expected ${expectedSeq}, got ${event.seq}; resyncing from snapshot`,
        );
        const gap: GapEvent = {
          marketplaceId: this.marketplaceId,
          expectedSeq,
          receivedSeq: event.seq,
        };
        for (const h of this._gapHandlers) {
          try { h(gap); } catch { /* one bad handler can't stop recovery */ }
        }
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
    if (_isStreamDropped(event)) {
      // The subscription restores itself; nothing to do but say so.
      console.warn(
        `[MarketView] WS transport error on marketplace ${this.marketplaceId}: ${event.exception.message}`,
      );
      return;
    }
    if (_isReconnected(event)) {
      this._reseedAfterReconnect();
      return;
    }
    if (_isFrameUnreadable(event)) {
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
   * Re-seed once the transport is back.
   *
   * Reconnecting is not this layer's job -- a `Reconnected` only arrives
   * because the listener already restored the subscription. Reseeding is: a
   * reconnect is the largest possible sequence gap, so _seedFromSnapshot()
   * (clear + REST snapshot + reapply + seq watermark) is what converges the
   * state, exactly as it does for a small one. If it fails the view is left
   * stale until the caller close()s and observe()s again.
   */
  private _reseedAfterReconnect(): void {
    if (this._resyncInFlight) return; // already handling
    this._resyncInFlight = true;
    this._seedComplete = false;
    void (async () => {
      let outcome: ReconnectEvent;
      try {
        await this._seedFromSnapshot();
        outcome = { marketplaceId: this.marketplaceId, success: true, reason: null };
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        console.error(
          `[MarketView] Reconnect failed on marketplace ${this.marketplaceId}; view is stale: ${reason}`,
        );
        outcome = { marketplaceId: this.marketplaceId, success: false, reason };
        // Leave _resyncInFlight=true so subsequent dispatches keep
        // buffering rather than corrupting state; caller can close()
        // and observe() to recover.
      }
      for (const h of this._reconnectHandlers) {
        try { h(outcome); } catch { /* don't let one bad handler stop dispatch */ }
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
    const traded = this._trades.update(orders);

    // Trades first: the more specific event, and a book handler that then reads
    // the tape sees the same trade the trade handler was just given. Both
    // aggregators are already current either way — what is ordered here is only
    // which handler hears about it first.
    for (const [marketId, fresh] of traded) {
      for (const h of this._tradeHandlers) {
        if (h.marketId === marketId) for (const trade of fresh) h.handler(trade);
      }
    }

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

function _isStreamDropped(event: FmEvent): event is StreamDropped {
  return typeof event === "object" && event !== null && (event as StreamDropped).kind === "stream-dropped";
}

function _isReconnected(event: FmEvent): event is Reconnected {
  return typeof event === "object" && event !== null && (event as Reconnected).kind === "reconnected";
}

function _isFrameUnreadable(event: FmEvent): event is FrameUnreadable {
  return typeof event === "object" && event !== null && (event as FrameUnreadable).kind === "frame-unreadable";
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

  orderBook(marketId: number): MarketBook | null {
    this._check();
    return this._shared.orderBook(marketId);
  }

  trades(marketId: number): Trades | null {
    this._check();
    return this._shared.trades(marketId);
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

  onOrderBookChange(marketId: number, handler: (b: MarketBook) => void): Subscription {
    this._check();
    const sub = this._shared.onOrderBookChange(marketId, handler);
    this._mySubscriptions.push(sub);
    return sub;
  }

  onTrade(marketId: number, handler: (t: Trade) => void): Subscription {
    this._check();
    const sub = this._shared.onTrade(marketId, handler);
    this._mySubscriptions.push(sub);
    return sub;
  }

  onHoldingChange(handler: (h: Holding) => void): Subscription {
    this._check();
    const sub = this._shared.onHoldingChange(handler);
    this._mySubscriptions.push(sub);
    return sub;
  }

  onGap(handler: (e: GapEvent) => void): Subscription {
    this._check();
    const sub = this._shared.onGap(handler);
    this._mySubscriptions.push(sub);
    return sub;
  }

  onReconnect(handler: (e: ReconnectEvent) => void): Subscription {
    this._check();
    const sub = this._shared.onReconnect(handler);
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
