"""Trade history maintained from WebSocket order events.

Port of fm.trades.Trades and fm.trades.MarketplaceTrades.
"""

from __future__ import annotations

import threading
from collections import deque

from .order_utils import is_cancel, is_consumed, is_split, is_symbol
from .types import Market, Order


class Trades:
    """Bounded FIFO queue of executed trades for a single market.

    Updated incrementally from WebSocket ``ORDERS-UPDATE`` events.
    Keeps the most recent *capacity* trades.
    """

    def __init__(self, market: Market, capacity: int = 100):
        if capacity < 1:
            raise ValueError("Capacity must be greater than zero.")
        self._market = market
        self._capacity = capacity
        self._container: deque[Order] = deque(maxlen=capacity)
        self._lock = threading.Lock()

    @property
    def market(self) -> Market:
        return self._market

    @property
    def market_id(self) -> int:
        return self._market.id

    @property
    def capacity(self) -> int:
        return self._capacity

    def size(self) -> int:
        with self._lock:
            return len(self._container)

    # -- update from WebSocket events --------------------------------------

    def update(self, orders: list[Order]) -> None:
        with self._lock:
            self._update(orders)

    def _update(self, orders: list[Order]) -> None:
        consumers: dict[int, Order] = {}

        for order in orders:
            if not is_symbol(self._market.symbol, order):
                continue

            if is_cancel(order):
                continue

            if is_split(order):
                continue

            if is_consumed(order):
                consumers[order.id] = order
                consumer = consumers.get(order.consumer)  # type: ignore[arg-type]

                if consumer is not None:
                    # The resting order is the one with the older original ID
                    if order.original < consumer.original:
                        self._container.append(order)
                    else:
                        self._container.append(consumer)

    # -- query -------------------------------------------------------------

    def most_recent_trades(self) -> list[Order]:
        with self._lock:
            return list(self._container)

    def most_recent_prices(self) -> list[int]:
        with self._lock:
            return [o.price for o in self._container]

    def drain(self) -> list[Order]:
        """Remove and return all trades from the queue."""
        with self._lock:
            trades = list(self._container)
            self._container.clear()
            return trades

    def clear(self) -> None:
        """Empty the trade tape — used by
        :class:`~fm.market_view.MarketView`'s gap-recovery flow before
        reseeding from the ``/v1/orders/recent-trades`` snapshot.
        """
        with self._lock:
            self._container.clear()


class MarketplaceTrades:
    """Container of :class:`Trades` instances, one per market.

    Port of ``MarketplaceTrades``.
    """

    def __init__(self, markets: list[Market], capacity: int = 100):
        self._trades: dict[int, Trades] = {
            m.id: Trades(m, capacity) for m in markets
        }

    def update(self, orders: list[Order]) -> None:
        for t in self._trades.values():
            t.update(orders)

    def most_recent_prices(self) -> list[list[int]]:
        return [
            t.most_recent_prices()
            for t in sorted(self._trades.values(), key=lambda t: t.market_id)
        ]

    def collection(self) -> list[Trades]:
        return list(self._trades.values())

    def __getitem__(self, market_id: int) -> Trades:
        return self._trades[market_id]

    def clear(self) -> None:
        """Empty every per-market trade tape — see
        :meth:`Trades.clear`.
        """
        for t in self._trades.values():
            t.clear()
