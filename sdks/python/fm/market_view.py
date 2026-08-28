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

import logging
import queue
import threading
from dataclasses import dataclass
from typing import TYPE_CHECKING, Callable, Optional

log = logging.getLogger(__name__)

from .events import NO_SEQ, OrdersUpdate, FrameUnreadable, Reconnected, StreamDropped
from .orderbook import MarketBook, MarketplaceBooks
from .trades import MarketplaceTrades, Trade, Trades
from .types import Holding, Market, Order, Session

if TYPE_CHECKING:
    from .client import Flexemarkets


# Handle returned by MarketView.on_* listener registrations. Call to
# unregister. Idempotent — multiple calls are no-ops.
Subscription = Callable[[], None]


@dataclass(frozen=True)
class GapEvent:
    """Fires when :class:`MarketView` detects a sequence-gap in the
    ORDERS-UPDATE WS stream — one or more frames were dropped between
    client and fm-server. After a gap, MarketView re-runs the REST
    snapshot recovery flow before applying further deltas; the gap
    event is the signal callers can wire into their own observability
    stack instead of relying on the SDK's default ``log.warning``.
    """

    marketplace_id: int
    expected_seq: int
    received_seq: int


@dataclass(frozen=True)
class ReconnectEvent:
    """Fires when :class:`MarketView` reacts to a
    :class:`~fm.events.StreamDropped` — either after the reconnect
    + resnapshot completes successfully, or after the attempt has
    failed and the view is left stale.
    """

    marketplace_id: int
    success: bool
    reason: Optional[str]


class MarketView:
    """Always-current view of a single marketplace.

    Obtained via ``Flexemarkets.observe(marketplace_id)``. The returned
    instance owns a WebSocket subscription that stays live until
    :meth:`close`.

    Phase 1 scope: This is a skeleton that wraps the existing
    ``Flexemarkets.listen()`` queue and dispatches events into the
    existing :class:`MarketplaceBooks`/:class:`MarketplaceTrades`
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
        self._order_books = MarketplaceBooks(self.markets)
        # 100 matches the default per-market Trades capacity. Plumb
        # through to observe() later if a caller needs deeper trade
        # scrollback.
        self._trades = MarketplaceTrades(self.markets, 100)

        self._session: Optional[Session] = None
        self._holding: Optional[Holding] = None

        self._session_handlers: list[Callable[[Session], None]] = []
        self._holding_handlers: list[Callable[[Holding], None]] = []
        self._book_handlers: list[tuple[int, Callable[[MarketBook], None]]] = []
        self._trade_handlers: list[tuple[int, Callable[[Trade], None]]] = []
        self._gap_handlers: list[Callable[[GapEvent], None]] = []
        self._reconnect_handlers: list[Callable[[ReconnectEvent], None]] = []

        self._queue: queue.Queue[object] = queue.Queue(maxsize=10_000)
        self._closed = False

        # Highest ORDERS-UPDATE seq applied so far. Deltas with
        # seq <= _last_applied_seq are skipped — they've already
        # been folded into the local state via the initial snapshot
        # or a previously-applied delta. NO_SEQ disables filtering
        # (older fm-server that doesn't stamp the header).
        self._last_applied_seq = NO_SEQ

        # Subscribe WS first so deltas start landing in the queue,
        # then fetch + apply the REST snapshot, only THEN start the
        # dispatcher. Any deltas that arrive between subscribe and
        # snapshot apply sit in the queue and get filtered by seq
        # when the dispatcher runs.
        #
        # Phase 2d: own the EventListener directly rather than calling
        # flexemarkets.listen() — that would clobber the singleton
        # _event_listener and prevent multiple shared views from
        # coexisting in one Flexemarkets.
        self._events = flexemarkets._connect_events(marketplace_id, self._queue)
        self._seed_from_snapshot()
        self._dispatcher = threading.Thread(
            target=self._drain, name=f"fm-market-view-{marketplace_id}", daemon=True
        )
        self._dispatcher.start()

    def _seed_from_snapshot(self) -> None:
        """Fetch the V1 active-orders and recent-trades snapshots,
        clear local state, apply both, and record the ``as_of_seq``.
        Used by both initial seeding (Phase 2a) and gap recovery
        (Phase 2b) — initial seed hits empty aggregators so the
        clear() is a no-op there.
        """
        orders = self._flexemarkets.active_orders(self.marketplace_id)
        trades = self._flexemarkets.recent_trades(self.marketplace_id)

        # Clear before reseeding so a resync doesn't double-add
        # against existing price levels.
        self._order_books.clear()
        self._trades.clear()

        if orders.body:
            self._order_books.update(orders.body)
        if trades.body:
            self._trades.update(trades.body)

        # Orders and trades flow through the same delta stream so
        # they share a single seq. Use the orders snapshot's value
        # as the watermark.
        self._last_applied_seq = orders.as_of_seq

    # -- read-side accessors ----------------------------------------------

    def order_book(self, market_id: int) -> Optional[MarketBook]:
        """Always-current order book for *market_id*; ``None`` if the
        market isn't in this marketplace.
        """
        self._ensure_open()
        return self._order_books.get(market_id)

    def trades(self, market_id: int) -> Optional[Trades]:
        """Always-current trade tape for *market_id*, most recent last;
        ``None`` if the market isn't in this marketplace.

        Maintained alongside :meth:`order_book`: seeded from the server's
        recent trades when the view opens, then kept current from the same
        delta stream. Each :class:`~fm.trades.Trade` on it carries both sides
        of its match -- the resting order that was taken and the aggressor
        that took it.
        """
        self._ensure_open()
        return self._trades.get(market_id)

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
        self, market_id: int, handler: Callable[[MarketBook], None]
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

    def on_trade(self, market_id: int, handler: Callable[[Trade], None]) -> Subscription:
        """Register a handler that fires for each trade on *market_id*.

        The missing member of the family :meth:`on_order_book_change` and
        :meth:`on_session_change` belong to. Without it, "tell me when a trade
        happens" is asked as "tell me when the book changed, then let me look"
        -- which answers a different question, since a book changes on every
        resting order and most changes are not trades.

        Fires **once per trade**, oldest first, rather than coalescing a batch
        the way :meth:`on_order_book_change` does. A trade is a discrete event
        with its own price and counterparties; collapsing two into one callback
        would lose one of them.

        Live deltas only. A gap or a reconnect re-seeds the tape from the
        server's snapshot, and those trades do not fire here -- they are not
        new, they are what was missed. :meth:`on_gap` and :meth:`on_reconnect`
        are how a caller learns that happened.
        """
        self._ensure_open()
        entry = (market_id, handler)
        self._trade_handlers.append(entry)

        def cancel() -> None:
            try:
                self._trade_handlers.remove(entry)
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

    def on_gap(self, handler: Callable[[GapEvent], None]) -> Subscription:
        """Register a handler that fires when MarketView detects a gap
        in the ORDERS-UPDATE seq stream. Use this to wire your own
        telemetry — by default the SDK calls ``log.warning`` but
        otherwise hides the recovery flow.
        """
        self._ensure_open()
        self._gap_handlers.append(handler)

        def cancel() -> None:
            try:
                self._gap_handlers.remove(handler)
            except ValueError:
                pass

        return cancel

    def on_reconnect(self, handler: Callable[[ReconnectEvent], None]) -> Subscription:
        """Register a handler that fires after the SDK reacts to a
        transport error — either when reconnect + resnapshot have
        completed, or when the attempt has failed and the view is
        left stale.
        """
        self._ensure_open()
        self._reconnect_handlers.append(handler)

        def cancel() -> None:
            try:
                self._reconnect_handlers.remove(handler)
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
        try:
            self._events.close()
        except Exception:
            pass
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

            if isinstance(event, OrdersUpdate):
                self._process_orders_update(event)
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

            if isinstance(event, StreamDropped):
                # The subscription restores itself; nothing to do but say so.
                log.warning(
                    "WS transport error on marketplace %d: %s",
                    self.marketplace_id,
                    event.exception,
                )
                continue

            if isinstance(event, Reconnected):
                self._reseed_after_reconnect()
                continue

            if isinstance(event, FrameUnreadable):
                # STOMP ERROR / parse failure. Logged for visibility;
                # reconnecting won't help with a malformed frame, so
                # we leave the view as-is.
                log.warning(
                    "WS error on marketplace %d: %s %s",
                    self.marketplace_id,
                    event.command,
                    event.body,
                )
                continue

            # VERSION, SESSION-LIST aren't reflected in the public
            # surface yet; ignore.

    def _process_orders_update(self, event: OrdersUpdate) -> None:
        """Phase 2b gap recovery + Phase 2a filter. When a delta
        arrives with ``seq > _last_applied_seq + 1``, one or more
        frames were dropped: refetch the V1 snapshot, clear local
        state, reseed, then fall through to the seq filter — if the
        gap-triggering delta's seq is now ``<=`` the refreshed
        ``_last_applied_seq`` it's silently skipped (snapshot covered
        it), otherwise it applies normally.
        """
        if (
            event.seq != NO_SEQ
            and self._last_applied_seq != NO_SEQ
            and event.seq > self._last_applied_seq + 1
        ):
            expected_seq = self._last_applied_seq + 1
            log.warning(
                "ORDERS-UPDATE seq gap on marketplace %d — expected %d, got %d; "
                "resyncing from snapshot",
                self.marketplace_id,
                expected_seq,
                event.seq,
            )
            gap = GapEvent(
                marketplace_id=self.marketplace_id,
                expected_seq=expected_seq,
                received_seq=event.seq,
            )
            for h in self._gap_handlers:
                try:
                    h(gap)
                except Exception:
                    pass  # one bad handler can't stop recovery
            self._seed_from_snapshot()

        if (
            event.seq != NO_SEQ
            and self._last_applied_seq != NO_SEQ
            and event.seq <= self._last_applied_seq
        ):
            return

        orders = event.orders
        touched = _market_ids_touched(orders)
        self._order_books.update(orders)
        traded = self._trades.update(orders)

        # Trades first: the more specific event, and a book handler that then
        # reads the tape sees the same trade the trade handler was just given.
        # Both aggregators are already current either way -- what is ordered
        # here is only which handler hears about it first.
        for market_id, fresh in traded.items():
            for entry_id, handler in self._trade_handlers:
                if entry_id == market_id:
                    for trade in fresh:
                        handler(trade)

        for market_id in touched:
            book = self._order_books.get(market_id)
            if book is None:
                continue
            for entry_id, handler in self._book_handlers:
                if entry_id == market_id:
                    handler(book)
        if event.seq != NO_SEQ:
            self._last_applied_seq = event.seq

    def _reseed_after_reconnect(self) -> None:
        """Re-seed once the transport is back.

        Reconnecting is not this layer's job -- :class:`~fm.events.Reconnected`
        only arrives because the listener already restored the subscription.
        Reseeding is: a reconnect is the largest possible sequence gap, so
        :meth:`_seed_from_snapshot` (clear + REST snapshot + reapply + seq
        watermark) is what converges the state, exactly as it does for a small
        one.

        If the reseed fails the view is left stale and the dispatcher
        continues; the caller's next access sees the last-applied state, and
        the ReconnectEvent says so.
        """
        try:
            self._seed_from_snapshot()
            outcome = ReconnectEvent(
                marketplace_id=self.marketplace_id, success=True, reason=None
            )
        except Exception as e:
            log.error(
                "Reconnect failed on marketplace %d; view is stale: %s",
                self.marketplace_id,
                e,
            )
            outcome = ReconnectEvent(
                marketplace_id=self.marketplace_id, success=False, reason=str(e)
            )
        for h in self._reconnect_handlers:
            try:
                h(outcome)
            except Exception:
                pass  # don't let one bad handler stop dispatch

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


class MarketViewHandle:
    """Reader-side handle on a refcounted :class:`MarketView`.

    Returned by :meth:`Flexemarkets.observe`; multiple handles for the
    same ``marketplace_id`` share one underlying view + WS
    subscription + materialized state. Each handle's :meth:`close`
    decrements the shared refcount and tears down the shared
    resources on the last close.

    Subscriptions registered via ``on_*_change`` on this handle are
    tracked locally and closed when the handle closes — so a handler
    doesn't keep firing into stale state after the handle is gone.
    """

    def __init__(self, shared: MarketView, on_close: Callable[[], None]):
        self._shared = shared
        self._on_close = on_close
        self._my_subscriptions: list[Subscription] = []
        self._closed = False

    @property
    def marketplace_id(self) -> int:
        return self._shared.marketplace_id

    @property
    def markets(self) -> list[Market]:
        self._check()
        return self._shared.markets

    def order_book(self, market_id: int) -> Optional[MarketBook]:
        self._check()
        return self._shared.order_book(market_id)

    def trades(self, market_id: int) -> Optional[Trades]:
        self._check()
        return self._shared.trades(market_id)

    def session(self) -> Optional[Session]:
        self._check()
        return self._shared.session()

    def holding(self) -> Optional[Holding]:
        self._check()
        return self._shared.holding()

    def on_session_change(self, handler: Callable[[Session], None]) -> Subscription:
        self._check()
        sub = self._shared.on_session_change(handler)
        self._my_subscriptions.append(sub)
        return sub

    def on_order_book_change(
        self, market_id: int, handler: Callable[[MarketBook], None]
    ) -> Subscription:
        self._check()
        sub = self._shared.on_order_book_change(market_id, handler)
        self._my_subscriptions.append(sub)
        return sub

    def on_trade(self, market_id: int, handler: Callable[[Trade], None]) -> Subscription:
        self._check()
        sub = self._shared.on_trade(market_id, handler)
        self._my_subscriptions.append(sub)
        return sub

    def on_holding_change(self, handler: Callable[[Holding], None]) -> Subscription:
        self._check()
        sub = self._shared.on_holding_change(handler)
        self._my_subscriptions.append(sub)
        return sub

    def on_gap(self, handler: Callable[[GapEvent], None]) -> Subscription:
        self._check()
        sub = self._shared.on_gap(handler)
        self._my_subscriptions.append(sub)
        return sub

    def on_reconnect(self, handler: Callable[[ReconnectEvent], None]) -> Subscription:
        self._check()
        sub = self._shared.on_reconnect(handler)
        self._my_subscriptions.append(sub)
        return sub

    def submit_limit(self, market_id: int, side: str, units: int, price: int) -> Order:
        self._check()
        return self._shared.submit_limit(market_id, side, units, price)

    def submit_cancel(self, market_id: int, original_id: int) -> Order:
        self._check()
        return self._shared.submit_cancel(market_id, original_id)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        for sub in self._my_subscriptions:
            try:
                sub()
            except Exception:
                pass
        self._my_subscriptions.clear()
        self._on_close()

    def __enter__(self) -> "MarketViewHandle":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    def _check(self) -> None:
        if self._closed:
            raise RuntimeError(
                f"MarketView handle for marketplace {self._shared.marketplace_id} is closed"
            )
