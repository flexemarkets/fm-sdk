/**
 * Trade history maintained from WebSocket order events.
 *
 * Port of fm.trades (Python) / fm.Trade and fm.Trades (Java).
 */

import { findOrder, isConsumed, isLimit, isResting, isSymbol } from "./order-utils.js";
import type { Market, Order } from "./types.js";

/**
 * One trade: the resting order, the order that crossed it, and the numbers
 * each side contributes.
 *
 * A trade is not a distinct thing on the wire. The exchange expresses one as a
 * pair of orders referring to each other, so every number a caller wants has to
 * be read off one side or the other — and *which* side is the part that is easy
 * to get wrong. Both sides are kept here, and the derived fields record the
 * choice rather than leaving each caller to make it again:
 *
 * - `price` and `units` come from `resting`, which carries the terms the trade
 *   happened on.
 * - `at` comes from `aggressor`, because the trade happened when the incoming
 *   order arrived, not when the quote it took was posted.
 *
 * The pairing rule is the one `TradesSummary` in fm-manager has always used: an
 * order that is a consumed limit whose consumer is also a limit is one side of
 * a match, and {@link isResting} says which side. Before this type existed the
 * tape kept only the resting order, so "who took this trade" answered with the
 * maker — a real participant, at a real price, in a complete-looking line that
 * named the wrong person.
 */
export interface Trade {
  resting: Order;
  aggressor: Order;
  price: number;
  units: number;
  at: Date | null;
}

/** A trade from its two sides, taking each derived field off the side that
 *  carries it. */
export function tradeOf(resting: Order, aggressor: Order): Trade {
  return {
    resting,
    aggressor,
    price: resting.price,
    units: resting.units,
    at: aggressor.lastModifiedDate,
  };
}

/**
 * Bounded FIFO queue of executed trades for a single market, newest last.
 *
 * Updated incrementally from WebSocket ORDERS-UPDATE events, and seeded from
 * the `/v1/orders/recent-trades` snapshot. Keeps the most recent `capacity`
 * trades.
 *
 * Each batch is sorted by the time the aggressor arrived before it is appended,
 * which is what makes "newest last" true rather than merely intended. Up to and
 * including fm-server 4.3.1 the snapshot MarketView seeds and re-seeds from — on
 * open, on a sequence gap, and after a reconnect — arrived newest *first*, so a
 * tape that appended in array order held its trades backwards and the caller
 * asking for the latest one got the oldest it had retained. Later servers send
 * it oldest-first; sorting here is what makes the tape's own contract
 * independent of which one answered.
 */
export class Trades {
  readonly market: Market;
  readonly capacity: number;
  private readonly _container: Trade[] = [];

  constructor(market: Market, capacity: number = 100) {
    if (capacity < 1) throw new Error("Capacity must be greater than zero.");
    this.market = market;
    this.capacity = capacity;
  }

  get marketId(): number {
    return this.market.id;
  }

  size(): number {
    return this._container.length;
  }

  // -- update from WebSocket events ------------------------------------------

  update(orders: Order[]): void {
    const found: Trade[] = [];

    for (const order of orders) {
      if (!isSymbol(this.market.symbol, order)) continue;
      if (!isLimit(order) || !isConsumed(order)) continue;

      const aggressor = findOrder(orders, order.consumer);
      if (aggressor === null || !isLimit(aggressor)) continue;
      if (!isResting(orders, order)) continue;

      found.push(tradeOf(order, aggressor));
    }

    // Stable since ES2019, so trades the server did not stamp keep the order
    // it delivered them in rather than being shuffled among themselves.
    found.sort((a, b) => time(a) - time(b));
    for (const trade of found) this._append(trade);
  }

  private _append(trade: Trade): void {
    this._container.push(trade);
    while (this._container.length > this.capacity) {
      this._container.shift();
    }
  }

  // -- query -----------------------------------------------------------------

  mostRecentTrades(): Trade[] {
    return [...this._container];
  }

  /** The most recent trade — what a caller asking "what just happened" wants.
   *  `null` when nothing has traded yet. */
  last(): Trade | null {
    return this._container.length > 0
      ? this._container[this._container.length - 1]!
      : null;
  }

  mostRecentPrices(): number[] {
    return this._container.map((t) => t.price);
  }

  drain(): Trade[] {
    const trades = [...this._container];
    this._container.length = 0;
    return trades;
  }

  /** Empty the trade tape — used by MarketView's gap-recovery flow. */
  clear(): void {
    this._container.length = 0;
  }
}

/**
 * Container of Trades instances, one per market.
 */
export class MarketplaceTrades {
  private readonly _trades = new Map<number, Trades>();

  constructor(markets: Market[], capacity: number = 100) {
    for (const m of markets) {
      this._trades.set(m.id, new Trades(m, capacity));
    }
  }

  update(orders: Order[]): void {
    for (const t of this._trades.values()) {
      t.update(orders);
    }
  }

  mostRecentPrices(): number[][] {
    return [...this._trades.values()]
      .sort((a, b) => a.marketId - b.marketId)
      .map((t) => t.mostRecentPrices());
  }

  collection(): Trades[] {
    return [...this._trades.values()];
  }

  get(marketId: number): Trades {
    return this._trades.get(marketId)!;
  }

  /** Empty every per-market trade tape — see {@link Trades.clear}. */
  clear(): void {
    for (const t of this._trades.values()) t.clear();
  }
}

/** Epoch millis for sorting, with unstamped trades last. */
function time(trade: Trade): number {
  return trade.at === null ? Number.POSITIVE_INFINITY : trade.at.getTime();
}
