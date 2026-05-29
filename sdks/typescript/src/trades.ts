/**
 * Trade history maintained from WebSocket order events.
 *
 * Port of fm.trades (Python) / fm.Trades (Java).
 */

import { isCancel, isConsumed, isSplit, isSymbol } from "./order-utils.js";
import type { Market, Order } from "./types.js";

/**
 * Bounded FIFO queue of executed trades for a single market.
 *
 * Updated incrementally from WebSocket ORDERS-UPDATE events.
 * Keeps the most recent `capacity` trades.
 */
export class Trades {
  readonly market: Market;
  readonly capacity: number;
  private readonly _container: Order[] = [];

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
    const consumers = new Map<number, Order>();

    for (const order of orders) {
      if (!isSymbol(this.market.symbol, order)) continue;
      if (isCancel(order)) continue;
      if (isSplit(order)) continue;

      if (isConsumed(order)) {
        consumers.set(order.id, order);
        const consumer = consumers.get(order.consumer!);

        if (consumer !== undefined) {
          // The resting order is the one with the older original ID
          if (order.original < consumer.original) {
            this._append(order);
          } else {
            this._append(consumer);
          }
        }
      }
    }
  }

  private _append(order: Order): void {
    this._container.push(order);
    while (this._container.length > this.capacity) {
      this._container.shift();
    }
  }

  // -- query -----------------------------------------------------------------

  mostRecentTrades(): Order[] {
    return [...this._container];
  }

  mostRecentPrices(): number[] {
    return this._container.map((o) => o.price);
  }

  drain(): Order[] {
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
