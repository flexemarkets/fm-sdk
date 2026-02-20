/**
 * Order book maintained from WebSocket order events.
 *
 * Port of fm.orderbook (Python) / fm.OrderBook (Java).
 */

import { isAvailable, isBuy, isCancel, isResting, isSplit, isSymbol } from "./order-utils.js";
import type { Market, Order } from "./types.js";

/**
 * Aggregated price-level order book for a single market.
 *
 * Updated incrementally from WebSocket ORDERS-UPDATE events.
 * Buys are sorted highest-first (best bid), sells lowest-first (best ask).
 */
export class OrderBook {
  readonly market: Market;
  private readonly _buys = new Map<number, number>(); // price → units
  private readonly _sells = new Map<number, number>(); // price → units
  private _initialized = false;

  constructor(market: Market) {
    this.market = market;
  }

  get symbol(): string {
    return this.market.symbol!;
  }

  get marketId(): number {
    return this.market.id;
  }

  // -- update from WebSocket events ------------------------------------------

  update(orders: Order[]): void {
    let isSplitBatch = false;

    for (const order of orders) {
      if (!isSymbol(this.symbol, order)) continue;

      const side = order.side!;
      const price = order.price;
      const units = order.units;

      // Add all available orders to the order book
      if (isAvailable(order)) {
        this._add(side, price, units);
        continue;
      }

      // During initialisation, disregard all non-available orders
      if (!this._initialized) continue;

      // Remove CANCEL orders
      if (isCancel(order)) {
        this._remove(side, price, units);
        continue;
      }

      // Remove split orders from book
      if (isSplit(order)) {
        isSplitBatch = true;
        this._remove(side, price, units);
        continue;
      }

      // If not a split, remove consumed resting order
      if (!isSplitBatch && isResting(orders, order)) {
        this._remove(side, price, units);
        continue;
      }
    }

    if (!this._initialized) {
      this._initialized = true;
    }
  }

  private _add(side: string, price: number, units: number): void {
    const levels = this._priceLevels(side);
    levels.set(price, (levels.get(price) ?? 0) + units);
  }

  private _remove(side: string, price: number, units: number): void {
    const levels = this._priceLevels(side);
    const updated = (levels.get(price) ?? 0) - units;
    if (updated < 1) {
      levels.delete(price);
    } else {
      levels.set(price, updated);
    }
  }

  private _priceLevels(side: string): Map<number, number> {
    return isBuy(side) ? this._buys : this._sells;
  }

  // -- query -----------------------------------------------------------------

  hasValue(side: string): boolean {
    return this._priceLevels(side).size > 0;
  }

  bestPrice(side: string): number {
    const levels = this._priceLevels(side);
    if (levels.size === 0) return -1;
    const prices = [...levels.keys()];
    return isBuy(side) ? Math.max(...prices) : Math.min(...prices);
  }

  bestUnits(side: string): number {
    const levels = this._priceLevels(side);
    if (levels.size === 0) return -1;
    const prices = [...levels.keys()];
    const best = isBuy(side) ? Math.max(...prices) : Math.min(...prices);
    return levels.get(best)!;
  }

  bestBuyPrice(): number {
    return this.bestPrice("BUY");
  }

  bestBuyUnits(): number {
    return this.bestUnits("BUY");
  }

  bestSellPrice(): number {
    return this.bestPrice("SELL");
  }

  bestSellUnits(): number {
    return this.bestUnits("SELL");
  }

  /** Price levels sorted highest-first (best bid first). */
  buyLevels(): [number, number][] {
    return [...this._buys.entries()].sort((a, b) => b[0] - a[0]);
  }

  /** Price levels sorted lowest-first (best ask first). */
  sellLevels(): [number, number][] {
    return [...this._sells.entries()].sort((a, b) => a[0] - b[0]);
  }
}

/**
 * Container of OrderBook instances, one per market.
 */
export class OrderBooks {
  private readonly _books = new Map<number, OrderBook>();

  constructor(markets: Market[]) {
    for (const m of markets) {
      this._books.set(m.id, new OrderBook(m));
    }
  }

  update(orders: Order[]): void {
    for (const book of this._books.values()) {
      book.update(orders);
    }
  }

  hasValue(marketId: number, side: string): boolean {
    return this._books.get(marketId)!.hasValue(side);
  }

  bestPrice(marketId: number, side: string): number {
    return this._books.get(marketId)!.bestPrice(side);
  }

  collection(): OrderBook[] {
    return [...this._books.values()];
  }

  get(marketId: number): OrderBook {
    return this._books.get(marketId)!;
  }
}
