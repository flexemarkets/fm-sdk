"""Order book maintained from WebSocket order events.

Port of fm.orderbook.Book and fm.orderbook.MarketplaceOrderBooks.
"""

from __future__ import annotations

import threading
from collections import defaultdict

from .order_utils import (find_order, is_available, is_cancel, is_resting,
                          is_split, is_symbol, is_buy, is_sell)
from .types import Market, Order


class Book:
    """Aggregated price-level order book for a single market.

    Updated incrementally from WebSocket ``ORDERS-UPDATE`` events.
    Buys are sorted highest-first (best bid), sells lowest-first (best ask).
    """

    def __init__(self, market: Market, depth: int = 0):
        self._market = market
        self._depth = depth
        self._buys: dict[int, int] = defaultdict(int)   # price → units
        self._sells: dict[int, int] = defaultdict(int)   # price → units
        self._initialized = False
        self._lock = threading.Lock()

    @property
    def market(self) -> Market:
        return self._market

    @property
    def symbol(self) -> str:
        return self._market.symbol  # type: ignore[return-value]

    @property
    def market_id(self) -> int:
        return self._market.id

    # -- update from WebSocket events --------------------------------------

    def update(self, orders: list[Order]) -> None:
        with self._lock:
            self._update(orders)

    def _update(self, orders: list[Order]) -> None:
        is_split_batch = False

        for order in orders:
            if not is_symbol(self.symbol, order):
                continue

            side = order.side
            price = order.price
            units = order.units

            # Add all available orders to the order book
            if is_available(order):
                self._add(side, price, units)
                continue

            # During initialisation, disregard all non-available orders
            if not self._initialized:
                continue

            if is_cancel(order):
                # fm-server broadcasts a cancel as two rows: the CANCEL, and the LIMIT it
                # consumed, which carries the cancel as its consumer. The resting branch
                # below removes that limit, because a cancelled limit was on the book and
                # isResting says so. Removing here as well takes the units off twice.
                #
                # Both removals landing on a level that held only the cancelled order left
                # it empty either way, which is why this survived: the one test that
                # covered a cancel used a single one-unit order. Give the level units from
                # a second order and the cancel takes that one down with it.
                #
                # So remove only when the order being cancelled is not in this batch --
                # which keeps a lone CANCEL working, and stops the pair double-counting.
                if find_order(orders, order.consumer) is None:
                    self._remove(side, price, units)
                continue

            # Remove split orders from book
            if is_split(order):
                is_split_batch = True
                self._remove(side, price, units)
                continue

            # If not a split, remove consumed resting order
            if not is_split_batch and is_resting(orders, order):
                self._remove(side, price, units)
                continue

        if not self._initialized:
            self._initialized = True

    def _add(self, side: str, price: int, units: int) -> None:
        levels = self._price_levels(side)
        levels[price] += units

    def _remove(self, side: str, price: int, units: int) -> None:
        levels = self._price_levels(side)
        updated = levels.get(price, 0) - units
        if updated < 1:
            levels.pop(price, None)
        else:
            levels[price] = updated

    def _price_levels(self, side: str) -> dict[int, int]:
        """The book a side belongs to, refusing anything that does not name one.

        This used to be ``self._buys if is_buy(side) else self._sells``, which
        is the complement of buy rather than a test for sell, so a ``None``
        side fell to the offer book. A ``None`` side is a real value on the
        wire -- a cancel carries none -- so that guess was reachable, and a
        book kept on a guess is wrong silently: units come off a side the
        order was never on.
        """
        if is_buy(side):
            return self._buys
        if is_sell(side):
            return self._sells
        raise ValueError(
            f"An order must name its side to be placed on a book; got: {side!r}")

    # -- query -------------------------------------------------------------

    def has_value(self, side: str) -> bool:
        with self._lock:
            return bool(self._price_levels(side))

    def best_price(self, side: str) -> int:
        with self._lock:
            levels = self._price_levels(side)
            if not levels:
                return -1
            return max(levels) if is_buy(side) else min(levels)

    def best_units(self, side: str) -> int:
        with self._lock:
            levels = self._price_levels(side)
            if not levels:
                return -1
            best = max(levels) if is_buy(side) else min(levels)
            return levels[best]

    def best_buy_price(self) -> int:
        return self.best_price("BUY")

    def best_buy_units(self) -> int:
        return self.best_units("BUY")

    def best_sell_price(self) -> int:
        return self.best_price("SELL")

    def best_sell_units(self) -> int:
        return self.best_units("SELL")

    def buy_levels(self) -> list[tuple[int, int]]:
        """Price levels sorted highest-first (best bid first)."""
        with self._lock:
            return sorted(self._buys.items(), key=lambda kv: -kv[0])

    def sell_levels(self) -> list[tuple[int, int]]:
        """Price levels sorted lowest-first (best ask first)."""
        with self._lock:
            return sorted(self._sells.items(), key=lambda kv: kv[0])

    def clear(self) -> None:
        """Reset to just-constructed state — empty levels,
        ``_initialized = False``. Used by
        :class:`~fm.desk.Desk`'s Phase 2b gap-recovery
        flow: refetch snapshot, clear, reapply via update so the next
        delta with ``is_available=False`` doesn't underflow against
        stale price levels.
        """
        with self._lock:
            self._buys.clear()
            self._sells.clear()
            self._initialized = False

    def __repr__(self) -> str:
        sell_levels = self.sell_levels()
        buy_levels = self.buy_levels()
        best_sell = self.best_sell_price()
        best_buy = self.best_buy_price()

        lines = ["-----BOOK-----"]
        if not sell_levels:
            lines.append("      --      ")
        for price, units in reversed(sell_levels):
            lines.append(f"S{units:3d}\t${price/100:5.2f}")
        lines.append("--------------")
        if best_sell < 0 or best_buy < 0:
            lines.append("spread        ")
        else:
            lines.append(f"spread  ${(best_sell - best_buy)/100:5.2f}")
        lines.append("--------------")
        if not buy_levels:
            lines.append("      --      ")
        for price, units in buy_levels:
            lines.append(f"B{units:3d}\t${price/100:5.2f}")
        lines.append("--------------")
        return "\n".join(lines)


class Books:
    """Container of :class:`Book` instances, one per market.

    Port of ``MarketplaceOrderBooks``.
    """

    def __init__(self, markets: list[Market], depth: int = 0):
        self._books: dict[int, Book] = {
            m.id: Book(m, depth) for m in markets
        }

    def update(self, orders: list[Order]) -> None:
        for book in self._books.values():
            book.update(orders)

    def has_value(self, market_id: int, side: str) -> bool:
        return self._books[market_id].has_value(side)

    def best_price(self, market_id: int, side: str) -> int:
        return self._books[market_id].best_price(side)

    def collection(self) -> list[Book]:
        return list(self._books.values())

    def get(self, market_id: int) -> Book | None:
        """Return the order book for *market_id*, or ``None`` if the
        market isn't part of this marketplace. Mirrors the Java + TS
        ``Books.get(marketId)`` signature so Desk can do a
        null-tolerant lookup.
        """
        return self._books.get(market_id)

    def clear(self) -> None:
        """Clear every contained book — see :meth:`Book.clear`."""
        for book in self._books.values():
            book.clear()

    def __getitem__(self, market_id: int) -> Book:
        return self._books[market_id]
