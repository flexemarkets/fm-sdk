"""Order status utilities.

Port of fm.net.TypesUtilities order-related helpers.
"""

from __future__ import annotations

from .types import Order


def is_cancel(order: Order) -> bool:
    return order.type is not None and order.type.upper() == Order.TYPE_CANCEL


def is_limit(order: Order) -> bool:
    return order.type is not None and order.type.upper() == Order.TYPE_LIMIT


def is_buy(side: str) -> bool:
    return side.upper() == Order.SIDE_BUY


def is_sell(side: str) -> bool:
    return side.upper() == Order.SIDE_SELL


def contra(side: str) -> str:
    return Order.SIDE_SELL if is_buy(side) else Order.SIDE_BUY


def is_available(order: Order) -> bool:
    """Order is resting on the book (consumer is None)."""
    return order.consumer is None


def is_consumed(order: Order) -> bool:
    """Order has been matched with another order."""
    return order.consumer is not None and order.consumer != 0


def is_split(order: Order) -> bool:
    """Order has been split into multiple fills."""
    return order.consumer is not None and order.consumer == 0


def is_symbol(symbol: str | None, order: Order) -> bool:
    if symbol is None:
        return True
    return order.symbol is not None and order.symbol.upper() == symbol.upper()


def is_submit(order: Order) -> bool:
    return is_cancel(order) or (order.id == order.original and order.id == order.supplier)


def find_order(orders: list[Order], order_id: int | None) -> Order | None:
    if order_id is None:
        return None
    for o in orders:
        if o.id == order_id:
            return o
    return None


def _is_cancelled(orders: list[Order], order: Order) -> bool:
    consumer = find_order(orders, order.consumer)
    if consumer is not None:
        if order.type != consumer.type:
            return True
    return False


def _in_trade_set(orders: list[Order], order_id: int) -> bool:
    for o in orders:
        if o.id == order_id:
            return True
    return False


def _is_traded(orders: list[Order], order: Order) -> bool:
    if is_limit(order) and is_consumed(order):
        consumer = find_order(orders, order.consumer)
        if consumer is not None and is_limit(consumer):
            return True
    return False


def _first_child(orders: list[Order], order: Order) -> Order | None:
    for o in orders:
        if order.id == o.original and not is_split(o):
            return o
    return None


def _is_created_earlier(order: Order, consumer: Order) -> bool:
    return order.id < consumer.id


def _is_older(orders: list[Order], o1: Order | None, o2: Order | None) -> bool:
    if o1 is None:
        return True
    if o2 is None:
        return False

    o1_in = _in_trade_set(orders, o1.original)
    o2_in = _in_trade_set(orders, o2.original)

    if not o1_in and o2_in:
        return True
    if o1_in and not o2_in:
        return False

    o1_original = o1 if o1.id == o1.original else find_order(orders, o1.original)
    o2_original = o2 if o2.id == o2.original else find_order(orders, o2.original)

    if o1_original is None or o2_original is None:
        return o1_original is None
    return _is_created_earlier(o1_original, o2_original)


def _is_supplier_older(orders: list[Order], order: Order | None) -> bool:
    if order is None:
        return False

    order_supplier = find_order(orders, order.supplier)
    consumer = find_order(orders, order.consumer)

    consumer_supplier_id = consumer.supplier if consumer is not None else None
    consumer_supplier = find_order(orders, consumer_supplier_id)

    return _is_older(orders, order_supplier, consumer_supplier)


def is_resting(orders: list[Order], order: Order) -> bool:
    """Determine if an order is currently resting on the book."""
    # available order is resting
    if is_available(order):
        return True

    # CANCEL order is not resting
    if is_cancel(order):
        return False

    # Cancelled LIMIT order is resting
    if _is_cancelled(orders, order):
        return True

    # order with supplier not in trade-set is resting
    if not _in_trade_set(orders, order.supplier):
        return True

    # order supplier older than consumer supplier
    if _is_traded(orders, order):
        return _is_supplier_older(orders, order)

    # split order with child younger than its consumer is resting
    return _is_supplier_older(orders, _first_child(orders, order))
