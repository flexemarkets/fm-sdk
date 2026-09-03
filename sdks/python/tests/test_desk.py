"""The desk, driven by a scripted stream rather than a live server.

Mirrors the Java DeskTest case for case. Python and TypeScript had desk
coverage only around the edges -- reconnect bookkeeping, subscription sharing
-- and nothing that drove one through seed, delta, sequence filtering and gap
recovery, which is where the three SDKs have actually been wrong together.

Cheaper here than in Java: a desk touches three methods on its client, and
duck typing means a fake needs only those, where the Java fake had to stub 58.

These assert the book's *contents*. A desk dispatches on its own thread, so
every assertion is made from a different thread than the one applying the
update; ``_await`` polls rather than sleeping, so a slow machine waits longer
instead of failing.
"""

from __future__ import annotations

import queue
import threading
import time
from typing import Any, Callable

import pytest

from fm.desk import Desk
from fm.orderbook import Book
from fm.snapshot import Snapshot
from fm.events import OrdersUpdate
from fm.types import Market, Order

MP = 7


def _market(market_id: int, symbol: str) -> Market:
    return Market(id=market_id, marketplace_id=MP, symbol=symbol,
                  price_minimum=0, price_maximum=10_000, price_tick=1,
                  unit_minimum=1, unit_maximum=100, unit_tick=1)


def _limit(market: Market, order_id: int, side: str, units: int, price: int) -> Order:
    return Order(id=order_id, original=order_id, supplier=order_id, consumer=None,
                 type="LIMIT", side=side, units=units, price=price,
                 marketplace_id=MP, session_id=1,
                 symbol=market.symbol, market_id=market.id)


class FakeClient:
    """Answers the three calls a desk makes, and hands the test its queue."""

    def __init__(self, markets: list[Market], active: Snapshot, recent: Snapshot):
        self._markets = markets
        self._active = active
        self._recent = recent
        self._queue: queue.Queue[object] | None = None
        self.active_reads = 0

    def next_active_orders(self, snapshot: Snapshot) -> None:
        """What the next seed reads, so a reseed can differ from the first."""
        self._active = snapshot

    def post(self, event: object) -> None:
        assert self._queue is not None, "nothing has subscribed yet"
        self._queue.put(event)

    # --- what a desk uses ---
    def active_orders(self, marketplace_id: int) -> Snapshot:
        self.active_reads += 1
        return self._active

    def recent_trades(self, marketplace_id: int) -> Snapshot:
        return self._recent

    def _connect_events(self, marketplace_id: int, q: "queue.Queue[object]") -> Any:
        self._queue = q

        class _Events:
            def close(self) -> None:
                pass

        return _Events()


def _await(what: str, condition: Callable[[], bool], seconds: float = 5.0) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.002)
    raise AssertionError(f"timed out waiting for: {what}")


def _desk(fake: FakeClient, markets: list[Market]) -> Desk:
    return Desk(fake, MP, markets)


def test_a_desk_seeds_its_books_from_the_snapshot():
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha],
                      Snapshot(body=[_limit(alpha, 101, "BUY", 5, 1000)], as_of_seq=4),
                      Snapshot(body=[], as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        assert desk.book(alpha.id).best_buy_price() == 1000
        assert desk.book(alpha.id).best_buy_units() == 5
    finally:
        desk.close()


def test_a_delta_after_the_seed_is_applied():
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha],
                      Snapshot(body=[_limit(alpha, 101, "BUY", 5, 1000)], as_of_seq=4),
                      Snapshot(body=[], as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "BUY", 3, 1100)], seq=5))
        _await("the better bid to land", lambda: desk.book(alpha.id).best_buy_price() == 1100)
        assert desk.book(alpha.id).best_buy_units() == 3
    finally:
        desk.close()


def test_a_delta_already_in_the_seed_is_not_applied_twice():
    """A book aggregates by price level, not by order id, so applying one
    twice adds its units twice and the book reads deeper than the market is."""
    alpha = _market(1, "ALPHA")
    resting = _limit(alpha, 101, "BUY", 5, 1000)
    fake = FakeClient([alpha], Snapshot(body=[resting], as_of_seq=4),
                      Snapshot(body=[], as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        # seq 4 == as_of_seq: the snapshot already reflects it.
        fake.post(OrdersUpdate(orders=[resting], seq=4))
        # A later delta to wait on, so the re-delivery has certainly been seen.
        fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "SELL", 2, 2000)], seq=5))
        _await("the marker delta to land", lambda: desk.book(alpha.id).best_sell_price() == 2000)

        assert desk.book(alpha.id).best_buy_units() == 5, "re-delivered seed order counted twice"
    finally:
        desk.close()


def test_books_and_tapes_cover_every_market():
    alpha, beta = _market(1, "ALPHA"), _market(2, "BETA")
    fake = FakeClient([alpha, beta], Snapshot(body=[], as_of_seq=1),
                      Snapshot(body=[], as_of_seq=1))
    desk = _desk(fake, [alpha, beta])
    try:
        assert len(desk.books()) == 2
        assert len(desk.tapes()) == 2
        assert {b.market_id for b in desk.books()} == {alpha.id, beta.id}
    finally:
        desk.close()


def test_a_gap_reseeds_the_book_from_the_snapshot_and_says_so():
    """A gap must leave the book *right*, not merely leave a message. The
    reseed answers a different book, so a resync that kept the stale one
    fails here."""
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha],
                      Snapshot(body=[_limit(alpha, 101, "BUY", 5, 1000)], as_of_seq=4),
                      Snapshot(body=[], as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        gaps: list[Any] = []
        desk.on_gap(gaps.append)

        fake.next_active_orders(
            Snapshot(body=[_limit(alpha, 201, "BUY", 9, 1500)], as_of_seq=40))

        # seq 41 with last-applied 4 is a gap of 36 frames.
        fake.post(OrdersUpdate(orders=[], seq=41))

        _await("the reseeded book", lambda: desk.book(alpha.id).best_buy_price() == 1500)
        assert desk.book(alpha.id).best_buy_units() == 9
        assert len(gaps) == 1, "on_gap fired"
        assert fake.active_reads == 2, "one seed at open, one at the gap"
    finally:
        desk.close()


def test_consecutive_frames_are_not_a_gap():
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha], Snapshot(body=[], as_of_seq=4), Snapshot(body=[], as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        gaps: list[Any] = []
        desk.on_gap(gaps.append)

        fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "BUY", 1, 900)], seq=5))
        fake.post(OrdersUpdate(orders=[_limit(alpha, 103, "BUY", 1, 950)], seq=6))
        _await("both deltas to land", lambda: desk.book(alpha.id).best_buy_price() == 950)

        assert gaps == []
        assert fake.active_reads == 1, "no reseed"
    finally:
        desk.close()
