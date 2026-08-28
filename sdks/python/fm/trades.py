"""Trade history maintained from WebSocket order events.

Port of fm.Trade, fm.Trades and fm.MarketplaceTrades.
"""

from __future__ import annotations

import threading
from collections import deque
from dataclasses import dataclass
from datetime import datetime

from .order_utils import find_order, is_consumed, is_limit, is_resting, is_symbol
from .types import Market, Order


@dataclass
class Trade:
    """One trade: the resting order, the order that crossed it, and the
    numbers each side contributes.

    A trade is not a distinct thing on the wire. The exchange expresses one as
    a pair of orders referring to each other, so every number a caller wants
    has to be read off one side or the other -- and *which* side is the part
    that is easy to get wrong. Both sides are kept here, and the derived
    fields record the choice rather than leaving each caller to make it again:

    * ``price`` and ``units`` come from ``resting``, which carries the terms
      the trade happened on.
    * ``at`` comes from ``aggressor``, because the trade happened when the
      incoming order arrived, not when the quote it took was posted.

    The pairing rule is the one ``TradesSummary`` in fm-manager has always
    used: an order that is a consumed limit whose consumer is also a limit is
    one side of a match, and :func:`~fm.order_utils.is_resting` says which
    side. Before this type existed the tape kept only the resting order, so
    "who took this trade" answered with the maker -- a real participant, at a
    real price, in a complete-looking line that named the wrong person.
    """

    resting: Order
    aggressor: Order
    price: int = 0
    units: int = 0
    at: "datetime | None" = None

    @staticmethod
    def of(resting: Order, aggressor: Order) -> "Trade":
        """A trade from its two sides, taking each derived field off the side
        that carries it.
        """
        return Trade(
            resting=resting,
            aggressor=aggressor,
            price=resting.price,
            units=resting.units,
            at=aggressor.last_modified_date,
        )


class Trades:
    """Bounded FIFO queue of executed trades for a single market, newest last.

    Updated incrementally from WebSocket ``ORDERS-UPDATE`` events, and seeded
    from the ``/v1/orders/recent-trades`` snapshot. Keeps the most recent
    *capacity* trades.

    Each batch is sorted by the time the aggressor arrived before it is
    appended, which is what makes "newest last" true rather than merely
    intended. Up to and including fm-server 4.3.1 the snapshot
    :class:`~fm.market_view.MarketView` seeds and re-seeds from -- on open, on
    a sequence gap, and after a reconnect -- arrived newest *first*, so a tape
    that appended in array order held its trades backwards and the caller
    asking for the latest one got the oldest it had retained. Later servers
    send it oldest-first; sorting here is what makes the tape's own contract
    independent of which one answered.
    """

    def __init__(self, market: Market, capacity: int = 100):
        if capacity < 1:
            raise ValueError("Capacity must be greater than zero.")
        self._market = market
        self._capacity = capacity
        self._container: deque[Trade] = deque(maxlen=capacity)
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

    def update(self, orders: list[Order]) -> list[Trade]:
        """Apply an orders update, and return the trades it added -- oldest
        first, and empty for the many updates that move the book without
        trading. What :class:`~fm.market_view.MarketView` hands to an
        ``on_trade`` handler.
        """
        with self._lock:
            return self._update(orders)

    def _update(self, orders: list[Order]) -> list[Trade]:
        found: list[Trade] = []

        for order in orders:
            if not is_symbol(self._market.symbol, order):
                continue
            if not is_limit(order) or not is_consumed(order):
                continue

            aggressor = find_order(orders, order.consumer)
            if aggressor is None or not is_limit(aggressor):
                continue
            if not is_resting(orders, order):
                continue

            found.append(Trade.of(order, aggressor))

        # Stable, and nothing without a timestamp is ever compared against
        # something with one -- the tuple's first element separates them, so
        # the placeholder below is only ever compared with itself.
        found.sort(key=lambda t: (t.at is None, t.at or _NO_TIME))
        self._container.extend(found)
        return found

    # -- query -------------------------------------------------------------

    def most_recent_trades(self) -> list[Trade]:
        with self._lock:
            return list(self._container)

    def last(self) -> "Trade | None":
        """The most recent trade -- what a caller asking "what just happened"
        wants. ``None`` when nothing has traded yet.
        """
        with self._lock:
            return self._container[-1] if self._container else None

    def most_recent_prices(self) -> list[int]:
        with self._lock:
            return [t.price for t in self._container]

    def drain(self) -> list[Trade]:
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

    def update(self, orders: list[Order]) -> dict[int, list[Trade]]:
        """Apply an orders update to every tape, and return the trades each
        market gained, keyed by market id, with markets that gained none left
        out -- which is most of them on most updates.

        :class:`~fm.market_view.MarketView` dispatches ``on_trade`` from this
        rather than diffing tape sizes, since a full tape drops its oldest as it
        takes a new one and the size does not move.
        """
        added: dict[int, list[Trade]] = {}
        for market_id, tape in self._trades.items():
            fresh = tape.update(orders)
            if fresh:
                added[market_id] = fresh
        return added

    def most_recent_prices(self) -> list[list[int]]:
        return [
            t.most_recent_prices()
            for t in sorted(self._trades.values(), key=lambda t: t.market_id)
        ]

    def collection(self) -> list[Trades]:
        return list(self._trades.values())

    def __getitem__(self, market_id: int) -> Trades:
        return self._trades[market_id]

    def get(self, market_id: int) -> "Trades | None":
        """That market's tape, or ``None`` when the market is not in this
        marketplace -- the lookup :class:`~fm.market_view.MarketView` needs,
        where an unknown id is an answer rather than a ``KeyError``.
        """
        return self._trades.get(market_id)

    def clear(self) -> None:
        """Empty every per-market trade tape — see
        :meth:`Trades.clear`.
        """
        for t in self._trades.values():
            t.clear()


_NO_TIME = datetime.min
