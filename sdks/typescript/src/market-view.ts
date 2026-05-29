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
import type { FmEvent } from "./stomp.js";
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

  static async open(flexemarkets: Flexemarkets, marketplaceId: number): Promise<DefaultMarketView> {
    const markets = await flexemarkets.markets(marketplaceId);
    return new DefaultMarketView(flexemarkets, marketplaceId, markets);
  }

  // 100 matches the default per-market Trades capacity. Plumb through to
  // observe() later if a caller needs deeper trade scrollback.
  private constructor(flexemarkets: Flexemarkets, marketplaceId: number, markets: Market[]) {
    this._flexemarkets = flexemarkets;
    this.marketplaceId = marketplaceId;
    this.markets = markets;
    this._orderBooks = new OrderBooks(markets);
    this._trades = new MarketplaceTrades(markets, 100);

    void flexemarkets.listen(marketplaceId, (event) => this._dispatch(event));
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
    // FmEvent is a discriminated union — Order[] is the ORDERS-UPDATE
    // delta, Session is SESSION-UPDATE, Holding is HOLDING-UPDATE.
    if (Array.isArray(event)) {
      const orders = event as Order[];
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
    // error lands in Phase 2.
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

function _isSession(event: FmEvent): event is Session {
  return typeof event === "object" && event !== null && "status" in event;
}

function _isHolding(event: FmEvent): event is Holding {
  return typeof event === "object" && event !== null && "securities" in event;
}
