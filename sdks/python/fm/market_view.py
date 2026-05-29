"""MarketView — always-current view of a single marketplace.

Hides transport details (WebSocket subscribe, snapshot+delta
reconciliation, sequence-gap recovery, reconnect) behind a small
read-side surface. Mirrors the Java + TypeScript SDKs; same staged
roadmap. See project_fm_sdk_canonical_client.

Phase 1 scope: API surface + skeleton that delegates to the existing
listen() / queue pipe. No reconciliation, no snapshot seed, no
sharing, no reconnect — those land in Phase 2.
"""

from __future__ import annotations

import queue
import threading
from typing import TYPE_CHECKING, Callable, Optional

from .events import WsException, WsTransportError
from .orderbook import OrderBook, OrderBooks
from .trades import MarketplaceTrades
from .types import Holding, Market, Order, Session

if TYPE_CHECKING:
    from .client import Flexemarkets


# Handle returned by MarketView.on_* listener registrations. Call to
# unregister. Idempotent — multiple calls are no-ops.
Subscription = Callable[[], None]


class MarketView:
    """Always-current view of a single marketplace.

    Obtained via ``Flexemarkets.observe(marketplace_id)``. The returned
    instance owns a WebSocket subscription that stays live until
    :meth:`close`.

    Phase 1 scope: This is a skeleton that wraps the existing
    ``Flexemarkets.listen()`` queue and dispatches events into the
    existing :class:`OrderBooks`/:class:`MarketplaceTrades`
    aggregators. *No reconciliation is wired yet* — REST-seed,
    sequence-gap recovery, per-identity sharing, and automatic
    reconnect land in Phase 2.
    """

    def __init__(
        self,
        flexemarkets: "Flexemarkets",
        marketplace_id: int,
        markets: list[Market],
    ):
        self._flexemarkets = flexemarkets
        self.marketplace_id = marketplace_id
        self.markets = list(markets)
        self._order_books = OrderBooks(self.markets)
        # 100 matches the default per-market Trades capacity. Plumb
        # through to observe() later if a caller needs deeper trade
        # scrollback.
        self._trades = MarketplaceTrades(self.markets, 100)

        self._session: Optional[Session] = None
        self._holding: Optional[Holding] = None

        self._session_handlers: list[Callable[[Session], None]] = []
        self._holding_handlers: list[Callable[[Holding], None]] = []
        self._book_handlers: list[tuple[int, Callable[[OrderBook], None]]] = []

        self._queue: queue.Queue[object] = queue.Queue(maxsize=10_000)
        self._closed = False

        flexemarkets.listen(marketplace_id, self._queue)
        self._dispatcher = threading.Thread(
            target=self._drain, name=f"fm-market-view-{marketplace_id}", daemon=True
        )
        self._dispatcher.start()

    # -- read-side accessors ----------------------------------------------

    def order_book(self, market_id: int) -> Optional[OrderBook]:
        """Always-current order book for *market_id*; ``None`` if the
        market isn't in this marketplace.
        """
        self._ensure_open()
        return self._order_books.get(market_id)

    def session(self) -> Optional[Session]:
        """Most-recent session update observed. ``None`` until the
        first SESSION-UPDATE frame lands.
        """
        self._ensure_open()
        return self._session

    def holding(self) -> Optional[Holding]:
        """The caller's holding for this marketplace. ``None`` until
        the first HOLDING-UPDATE frame lands.
        """
        self._ensure_open()
        return self._holding

    # -- listener registration --------------------------------------------

    def on_session_change(self, handler: Callable[[Session], None]) -> Subscription:
        self._ensure_open()
        self._session_handlers.append(handler)

        def cancel() -> None:
            try:
                self._session_handlers.remove(handler)
            except ValueError:
                pass

        return cancel

    def on_order_book_change(
        self, market_id: int, handler: Callable[[OrderBook], None]
    ) -> Subscription:
        self._ensure_open()
        entry = (market_id, handler)
        self._book_handlers.append(entry)

        def cancel() -> None:
            try:
                self._book_handlers.remove(entry)
            except ValueError:
                pass

        return cancel

    def on_holding_change(self, handler: Callable[[Holding], None]) -> Subscription:
        self._ensure_open()
        self._holding_handlers.append(handler)

        def cancel() -> None:
            try:
                self._holding_handlers.remove(handler)
            except ValueError:
                pass

        return cancel

    # -- write-side pass-throughs -----------------------------------------

    def submit_limit(self, market_id: int, side: str, units: int, price: int) -> Order:
        self._ensure_open()
        return self._flexemarkets.submit_limit(
            self.marketplace_id, market_id, side, units, price
        )

    def submit_cancel(self, market_id: int, original_id: int) -> Order:
        self._ensure_open()
        return self._flexemarkets.submit_cancel(self.marketplace_id, market_id, original_id)

    # -- lifecycle --------------------------------------------------------

    def close(self) -> None:
        """Release the WS subscription. After close, accessors raise
        :class:`RuntimeError` and new handler registrations are
        rejected. Idempotent.
        """
        if self._closed:
            return
        self._closed = True
        # Flexemarkets is owned by the caller; we don't close it. The
        # dispatcher thread is daemon so it'll exit with the process,
        # and the next queue.get() with timeout will see _closed and
        # bail.

    def __enter__(self) -> "MarketView":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    # -- internals --------------------------------------------------------

    def _drain(self) -> None:
        while not self._closed:
            try:
                event = self._queue.get(timeout=1.0)
            except queue.Empty:
                continue

            if isinstance(event, list):
                # ORDERS-UPDATE delta — list of Order
                touched = _market_ids_touched(event)
                self._order_books.update(event)
                self._trades.update(event)
                for market_id in touched:
                    book = self._order_books.get(market_id)
                    if book is None:
                        continue
                    for entry_id, handler in self._book_handlers:
                        if entry_id == market_id:
                            handler(book)
                continue

            if isinstance(event, Session):
                self._session = event
                for h in self._session_handlers:
                    h(event)
                continue

            if isinstance(event, Holding):
                self._holding = event
                for h in self._holding_handlers:
                    h(event)
                continue

            if isinstance(event, (WsTransportError, WsException)):
                # Reconnect handling lands in Phase 2. For now the
                # dispatcher exits and the view becomes useless;
                # callers must close() and observe() again.
                break

            # VERSION, SESSION-LIST aren't reflected in the public
            # surface yet; ignore.

    def _ensure_open(self) -> None:
        if self._closed:
            raise RuntimeError(
                f"MarketView for marketplace {self.marketplace_id} is closed"
            )


def _market_ids_touched(orders: list[Order]) -> list[int]:
    seen: set[int] = set()
    for o in orders:
        seen.add(o.market_id)
    return list(seen)
