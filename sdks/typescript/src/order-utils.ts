/**
 * Order status utilities.
 *
 * Port of fm.order_utils (Python) / fm.OrderUtils (Java).
 */

import { Order, toOrderType, toSide, type OrderSide } from "./types.js";

export function isCancel(order: Order): boolean {
  return toOrderType(order.type) === "CANCEL";
}

export function isLimit(order: Order): boolean {
  return toOrderType(order.type) === "LIMIT";
}

// These take `string` rather than `OrderSide` on purpose: they are the lenient
// door. A caller holding a value off the wire, or out of a config file, can
// ask without narrowing it first -- which is what the old toUpperCase()
// comparison allowed and what removing it would quietly take away.
export function isBuy(side: string | null | undefined): boolean {
  return toSide(side) === "BUY";
}

export function isSell(side: string | null | undefined): boolean {
  return toSide(side) === "SELL";
}

export function contra(side: string): OrderSide {
  return isBuy(side) ? "SELL" : "BUY";
}

/** Order is resting on the book (consumer is null). */
export function isAvailable(order: Order): boolean {
  return order.consumer === null || order.consumer === undefined;
}

/** Order has been matched with another order. */
export function isConsumed(order: Order): boolean {
  return order.consumer !== null && order.consumer !== undefined && order.consumer !== 0;
}

/** Order has been split into multiple fills. */
export function isSplit(order: Order): boolean {
  return order.consumer !== null && order.consumer !== undefined && order.consumer === 0;
}

export function isSymbol(symbol: string | null, order: Order): boolean {
  if (symbol === null || symbol === undefined) return true;
  return order.symbol?.toUpperCase() === symbol.toUpperCase();
}

export function isSubmit(order: Order): boolean {
  return isCancel(order) || (order.id === order.original && order.id === order.supplier);
}

export function findOrder(orders: Order[], orderId: number | null | undefined): Order | null {
  if (orderId === null || orderId === undefined) return null;
  for (const o of orders) {
    if (o.id === orderId) return o;
  }
  return null;
}

function isCancelled(orders: Order[], order: Order): boolean {
  const consumer = findOrder(orders, order.consumer);
  if (consumer !== null) {
    if (order.type !== consumer.type) return true;
  }
  return false;
}

function inTradeSet(orders: Order[], orderId: number): boolean {
  for (const o of orders) {
    if (o.id === orderId) return true;
  }
  return false;
}

function isTraded(orders: Order[], order: Order): boolean {
  if (isLimit(order) && isConsumed(order)) {
    const consumer = findOrder(orders, order.consumer);
    if (consumer !== null && isLimit(consumer)) return true;
  }
  return false;
}

function firstChild(orders: Order[], order: Order): Order | null {
  for (const o of orders) {
    if (order.id === o.original && !isSplit(o)) return o;
  }
  return null;
}

function isCreatedEarlier(order: Order, consumer: Order): boolean {
  return order.id < consumer.id;
}

function isOlder(orders: Order[], o1: Order | null, o2: Order | null): boolean {
  if (o1 === null) return true;
  if (o2 === null) return false;

  const o1In = inTradeSet(orders, o1.original);
  const o2In = inTradeSet(orders, o2.original);

  if (!o1In && o2In) return true;
  if (o1In && !o2In) return false;

  const o1Original = o1.id === o1.original ? o1 : findOrder(orders, o1.original);
  const o2Original = o2.id === o2.original ? o2 : findOrder(orders, o2.original);

  if (o1Original === null || o2Original === null) {
    return o1Original === null;
  }
  return isCreatedEarlier(o1Original, o2Original);
}

function isSupplierOlder(orders: Order[], order: Order | null): boolean {
  if (order === null) return false;

  const orderSupplier = findOrder(orders, order.supplier);
  const consumer = findOrder(orders, order.consumer);

  const consumerSupplierId = consumer !== null ? consumer.supplier : null;
  const consumerSupplier = findOrder(orders, consumerSupplierId);

  return isOlder(orders, orderSupplier, consumerSupplier);
}

/** Determine if an order is currently resting on the book. */
export function isResting(orders: Order[], order: Order): boolean {
  // available order is resting
  if (isAvailable(order)) return true;

  // CANCEL order is not resting
  if (isCancel(order)) return false;

  // Cancelled LIMIT order is resting
  if (isCancelled(orders, order)) return true;

  // order with supplier not in trade-set is resting
  if (!inTradeSet(orders, order.supplier)) return true;

  // order supplier older than consumer supplier
  if (isTraded(orders, order)) return isSupplierOlder(orders, order);

  // split order with child younger than its consumer is resting
  return isSupplierOlder(orders, firstChild(orders, order));
}
