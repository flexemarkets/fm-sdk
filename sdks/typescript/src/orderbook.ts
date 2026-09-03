/**
 * Order book maintained from WebSocket order events.
 *
 * Port of fm.orderbook (Python) / fm.MarketBook (Java).
 */

import {
  findOrder, isAvailable, isBuy, isSell, isCancel, isResting, isSplit, isSymbol,
} from "./order-utils.js";
import type { Market, Order } from "./types.js";

/**
 * Aggregated price-level order book for a single market.
 *
 * Updated incrementally from WebSocket ORDERS-UPDATE events.
 * Buys are sorted highest-first (best bid), sells lowest-first (best ask).
 */
export class MarketBook {
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

      if (isCancel(order)) {
        // fm-server broadcasts a cancel as two rows: the CANCEL, and the LIMIT it
        // consumed, which carries the cancel as its consumer. The resting branch
        // below removes that limit, because a cancelled limit was on the book and
        // isResting says so. Removing here as well takes the units off twice.
        //
        // Both removals landing on a level that held only the cancelled order left
        // it empty either way, which is why this survived: the one test that
        // covered a cancel used a single one-unit order. Give the level units from
        // a second order and the cancel takes that one down with it.
        //
        // So remove only when the order being cancelled is not in this batch --
        // which keeps a lone CANCEL working, and stops the pair double-counting.
        if (findOrder(orders, order.consumer) === null) {
          this._remove(side, price, units);
        }
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

  /**
   * The book a side belongs to, refusing anything that does not name one.
   *
   * This used to be `isBuy(side) ? this._buys : this._sells`, which is the
   * complement of buy rather than a test for sell, so a null side fell to the
   * offer book. A null side is a real value on the wire -- a cancel carries
   * none -- so that guess was reachable, and a book kept on a guess is wrong
   * silently: units come off a side the order was never on.
   */
  private _priceLevels(side: string): Map<number, number> {
    if (isBuy(side)) return this._buys;
    if (isSell(side)) return this._sells;
    throw new Error(`An order must name its side to be placed on a book; got: ${side}`);
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

  /**
   * Reset to just-constructed state — empty levels, initialized=false.
   * Used by MarketView's Phase 2b gap-recovery: refetch the snapshot,
   * clear(), reapply via update(), so the next delta with
   * isAvailable=false doesn't underflow against stale levels.
   */
  clear(): void {
    this._buys.clear();
    this._sells.clear();
    this._initialized = false;
  }
}

/**
 * Container of MarketBook instances, one per market.
 */
export class MarketplaceBooks {
  private readonly _books = new Map<number, MarketBook>();

  constructor(markets: Market[]) {
    for (const m of markets) {
      this._books.set(m.id, new MarketBook(m));
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

  collection(): MarketBook[] {
    return [...this._books.values()];
  }

  get(marketId: number): MarketBook {
    return this._books.get(marketId)!;
  }

  /** Clear every contained book — see {@link MarketBook.clear}. */
  clear(): void {
    for (const book of this._books.values()) book.clear();
  }
}
