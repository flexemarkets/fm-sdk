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

import logging
import queue
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

import pytest

from fm.desk import Desk, DeskRecovery
from fm.orderbook import Book
from fm.snapshot import Snapshot
from fm.events import FrameUnreadable, OrdersUpdate, StreamDropped, StreamReconnected
from fm.types import Holding, Market, Order, Session

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
        self.active_failure: Exception | None = None
        # The trade reads made market by market, each as "market_id/size",
        # and those made for every market at once.
        self.market_trade_reads: list[str] = []
        self.marketplace_trade_reads = 0
        self.cancels: list[str] = []
        self.stream_closes = 0

    def next_active_orders(self, snapshot: Snapshot) -> None:
        """What the next seed reads, so a reseed can differ from the first."""
        self._active = snapshot

    def post(self, event: object) -> None:
        assert self._queue is not None, "nothing has subscribed yet"
        self._queue.put(event)

    # --- what a desk uses ---
    def active_orders(self, marketplace_id: int) -> Snapshot:
        self.active_reads += 1
        if self.active_failure is not None:
            raise self.active_failure
        return self._active

    def recent_trades(self, marketplace_id: int, size: int = 1000,
                      market_id: int | None = None) -> Snapshot:
        if market_id is None:
            self.marketplace_trade_reads += 1
            return self._recent
        # One market's legs out of the recent snapshot, as the server answers ?market=.
        self.market_trade_reads.append(f"{market_id}/{size}")
        return Snapshot(body=[o for o in self._recent.body if o.market_id == market_id],
                        as_of_seq=self._recent.as_of_seq)

    def submit_cancel(self, marketplace_id: int, market_id: int, original_id: int) -> Order:
        self.cancels.append(f"{marketplace_id}/{market_id} {original_id}")
        return Order(id=original_id + 1, original=original_id, type="CANCEL")

    def _connect_events(self, marketplace_id: int, q: "queue.Queue[object]") -> Any:
        self._queue = q
        fake = self

        class _Events:
            def close(self) -> None:
                fake.stream_closes += 1

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


def _trade(market: Market, resting_id: int, aggressor_id: int, price: int) -> list[Order]:
    """Both legs of one trade: *resting_id* rested, *aggressor_id* took it."""
    rested = datetime(2026, 10, 8, 10, 0, tzinfo=timezone.utc) + timedelta(seconds=resting_id)
    taken = rested + timedelta(seconds=1000)
    return [
        Order(id=resting_id, original=resting_id, supplier=resting_id, consumer=aggressor_id,
              type="LIMIT", side="SELL", units=1, price=price, owner_id=900,
              marketplace_id=MP, session_id=1, symbol=market.symbol, market_id=market.id,
              created_date=rested, last_modified_date=taken),
        Order(id=aggressor_id, original=aggressor_id, supplier=aggressor_id, consumer=resting_id,
              type="LIMIT", side="BUY", units=1, price=price, owner_id=901,
              marketplace_id=MP, session_id=1, symbol=market.symbol, market_id=market.id,
              created_date=taken, last_modified_date=taken),
    ]


def test_each_tape_is_seeded_from_its_own_markets_read():
    """Read for every market at once, a busy market's legs filled the shared
    limit and a quiet market's tape came up empty though it had traded
    (fm-server#1029)."""
    busy, quiet = _market(1, "BUSY"), _market(2, "QUIET")
    legs = _trade(busy, 101, 102, 500) + _trade(quiet, 11, 12, 700)
    fake = FakeClient([busy, quiet], Snapshot(body=[], as_of_seq=4), Snapshot(body=legs, as_of_seq=4))
    desk = _desk(fake, [busy, quiet])
    try:
        assert fake.market_trade_reads == [
            f"{busy.id}/{2 * desk.tape(busy.id).capacity}",
            f"{quiet.id}/{2 * desk.tape(quiet.id).capacity}",
        ]
        assert fake.marketplace_trade_reads == 0, "no read for every market at once"
        assert desk.tape(quiet.id).most_recent_prices() == [700]
    finally:
        desk.close()


def test_a_trade_in_the_seed_and_again_in_a_delta_is_kept_once():
    """The trades snapshot is read after the orders snapshot whose sequence
    the desk follows, so a trade made in between is in the seed and again in
    a delta past the watermark. The tape keeps it once, and on_trade does not
    announce it a second time."""
    alpha = _market(1, "ALPHA")
    trade = _trade(alpha, 101, 102, 500)
    fake = FakeClient([alpha], Snapshot(body=[], as_of_seq=4), Snapshot(body=trade, as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        announced: list[Any] = []
        desk.on_trade(alpha.id, announced.append)

        fake.post(OrdersUpdate(orders=trade, seq=5))
        fake.post(OrdersUpdate(orders=[_limit(alpha, 200, "SELL", 2, 2000)], seq=6))
        _await("the marker delta to land", lambda: desk.book(alpha.id).best_sell_price() == 2000)

        assert desk.tape(alpha.id).size() == 1
        assert announced == []
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


def test_a_reconnect_reseeds_the_book_and_says_so():
    """A reconnect is the largest possible gap: the desk reseeds from the
    snapshot and tells its recovery handlers. Mirrors Java's
    aReconnectReseedsTheBookAndSaysSo; no test reached this before 2026-10-06."""
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha],
                      Snapshot(body=[_limit(alpha, 101, "BUY", 5, 1000)], as_of_seq=4),
                      Snapshot(body=[], as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        recoveries: list[DeskRecovery] = []
        desk.on_recovery(recoveries.append)
        fake.next_active_orders(Snapshot(body=[_limit(alpha, 201, "BUY", 9, 1500)], as_of_seq=40))

        fake.post(StreamReconnected(marketplace_id=MP))

        _await("the reseeded book", lambda: desk.book(alpha.id).best_buy_price() == 1500)
        _await("the recovery handler", lambda: len(recoveries) == 1)
        assert recoveries[0] == DeskRecovery(marketplace_id=MP, success=True, reason=None)
    finally:
        desk.close()


def test_a_reseed_that_fails_says_the_desk_is_stale_and_keeps_going():
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha],
                      Snapshot(body=[_limit(alpha, 101, "BUY", 5, 1000)], as_of_seq=4),
                      Snapshot(body=[], as_of_seq=4))
    desk = _desk(fake, [alpha])
    try:
        recoveries: list[DeskRecovery] = []
        desk.on_recovery(recoveries.append)
        fake.active_failure = RuntimeError("503 from the server")

        fake.post(StreamReconnected(marketplace_id=MP))
        _await("the recovery handler", lambda: len(recoveries) == 1)

        assert recoveries[0].success is False
        assert "503" in (recoveries[0].reason or "")

        fake.active_failure = None
        fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "BUY", 3, 1100)], seq=5))
        _await("a delta after the failed reseed", lambda: desk.book(alpha.id).best_buy_price() == 1100)
    finally:
        desk.close()


def test_session_and_holding_updates_reach_the_desk_and_its_handlers():
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha], Snapshot(body=[], as_of_seq=1), Snapshot(body=[], as_of_seq=1))
    desk = _desk(fake, [alpha])
    try:
        sessions: list[Session] = []
        holdings: list[Holding] = []
        desk.on_session_change(sessions.append)
        desk.on_holding_change(holdings.append)

        fake.post(Session(marketplace_id=MP, id=30, original=30, state="PAUSED"))
        fake.post(Holding(marketplace_id=MP, session_id=30, cash=1500))

        _await("both handlers", lambda: sessions and holdings)
        assert desk.session().state == "PAUSED"
        assert desk.holding().cash == 1500
    finally:
        desk.close()


def test_a_handler_that_raises_does_not_stop_the_desk():
    """A caller's handler is the caller's code. One that raises must not end
    the dispatcher: gap and recovery handlers were already guarded, but a
    session, holding, book or trade handler that raised took the thread down,
    and the desk stopped applying anything -- silently, books frozen."""
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha], Snapshot(body=[], as_of_seq=1), Snapshot(body=[], as_of_seq=1))
    desk = _desk(fake, [alpha])
    try:
        def explode(_event: object) -> None:
            raise ValueError("a bug in the caller's handler")

        desk.on_session_change(explode)
        desk.on_holding_change(explode)
        desk.on_book_change(alpha.id, explode)

        fake.post(Session(marketplace_id=MP, id=30, original=30, state="OPEN"))
        fake.post(Holding(marketplace_id=MP, session_id=30, cash=1500))
        fake.post(OrdersUpdate(orders=[_limit(alpha, 101, "BUY", 5, 1000)], seq=2))
        fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "BUY", 3, 1100)], seq=3))

        _await("the update after the raising handlers", lambda: desk.book(alpha.id).best_buy_price() == 1100)
        assert desk.holding().cash == 1500
    finally:
        desk.close()


def test_a_closed_desk_refuses_every_read_books_included():
    """close() promises that accessors raise afterwards. books() alone did
    not: it answered with the books as the last frame left them, frozen and
    looking current -- which is what a handle reads once Flexemarkets.close()
    has closed the desk under it."""
    alpha = _market(1, "ALPHA")
    fake = FakeClient([alpha], Snapshot(body=[_limit(alpha, 101, "BUY", 5, 1000)], as_of_seq=1),
                      Snapshot(body=[], as_of_seq=1))
    desk = _desk(fake, [alpha])
    desk.close()

    for read in (desk.books, desk.tapes, desk.session, desk.holding,
                 lambda: desk.book(alpha.id), lambda: desk.tape(alpha.id)):
        with pytest.raises(RuntimeError, match="Desk for marketplace 7 is closed"):
            read()


def _empty_desk(*markets: Market) -> tuple[FakeClient, Desk]:
    fake = FakeClient(list(markets), Snapshot(body=[], as_of_seq=1), Snapshot(body=[], as_of_seq=1))
    return fake, _desk(fake, list(markets))


def test_a_trade_reaches_its_markets_trade_handlers_before_its_book_handlers():
    """on_trade had never fired in a test: the one test that registered it
    checked that it stayed silent."""
    alpha, beta = _market(1, "ALPHA"), _market(2, "BETA")
    fake, desk = _empty_desk(alpha, beta)
    try:
        heard: list[tuple[str, Any]] = []
        desk.on_trade(alpha.id, lambda trade: heard.append(("trade", trade)))
        desk.on_trade(beta.id, lambda trade: heard.append(("beta trade", trade)))
        desk.on_book_change(alpha.id, lambda book: heard.append(("book", book.market_id)))

        fake.post(OrdersUpdate(orders=_trade(alpha, 101, 102, 500), seq=2))
        _await("both handlers", lambda: len(heard) >= 2)

        assert [kind for kind, _ in heard] == ["trade", "book"]
        trade = heard[0][1]
        assert (trade.resting.id, trade.aggressor.id, trade.price) == (101, 102, 500)
    finally:
        desk.close()


def test_a_handler_for_a_market_the_desk_does_not_keep_never_fires():
    alpha, stranger = _market(1, "ALPHA"), _market(99, "ALPHA")
    fake, desk = _empty_desk(alpha)
    try:
        heard: list[Any] = []
        desk.on_book_change(stranger.id, heard.append)

        fake.post(OrdersUpdate(orders=[_limit(stranger, 201, "BUY", 1, 900)], seq=2))
        fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "BUY", 1, 950)], seq=3))
        _await("the marker delta", lambda: desk.book(alpha.id).best_buy_price() == 950)

        assert heard == []
    finally:
        desk.close()


@pytest.mark.parametrize("register, event", [
    (lambda d, h: d.on_session_change(h), Session(marketplace_id=MP, id=30, original=30, state="OPEN")),
    (lambda d, h: d.on_holding_change(h), Holding(marketplace_id=MP, session_id=30, cash=1500)),
    (lambda d, h: d.on_book_change(1, h), OrdersUpdate(orders=[], seq=2)),
    (lambda d, h: d.on_trade(1, h), None),
    (lambda d, h: d.on_gap(h), OrdersUpdate(orders=[], seq=50)),
    (lambda d, h: d.on_recovery(h), StreamReconnected(marketplace_id=MP)),
], ids=["session", "holding", "book", "trade", "gap", "recovery"])
def test_a_cancelled_handler_hears_nothing_and_cancelling_twice_is_harmless(register, event):
    alpha = _market(1, "ALPHA")
    fake, desk = _empty_desk(alpha)
    try:
        heard: list[Any] = []
        cancel = register(desk, heard.append)
        cancel()
        cancel()

        if event is None:
            event = OrdersUpdate(orders=_trade(alpha, 101, 102, 500), seq=2)
        fake.post(event)
        fake.post(OrdersUpdate(orders=[_limit(alpha, 300, "SELL", 1, 9000)], seq=60))
        _await("the marker delta", lambda: desk.book(alpha.id).best_sell_price() == 9000)

        assert heard == []
    finally:
        desk.close()


def test_a_gap_or_recovery_handler_that_raises_does_not_stop_the_next_one():
    alpha = _market(1, "ALPHA")
    fake, desk = _empty_desk(alpha)
    try:
        def explode(_event: object) -> None:
            raise ValueError("a bug in the caller's handler")

        gaps: list[Any] = []
        recoveries: list[DeskRecovery] = []
        desk.on_gap(explode)
        desk.on_gap(gaps.append)
        desk.on_recovery(explode)
        desk.on_recovery(recoveries.append)

        fake.post(OrdersUpdate(orders=[], seq=9))
        fake.post(StreamReconnected(marketplace_id=MP))
        _await("the second recovery handler", lambda: recoveries)

        assert len(gaps) == 1
        assert recoveries[0].success is True
    finally:
        desk.close()


def test_a_dropped_stream_and_an_unreadable_frame_are_logged_and_the_desk_carries_on(caplog):
    alpha = _market(1, "ALPHA")
    fake, desk = _empty_desk(alpha)
    try:
        with caplog.at_level(logging.WARNING, logger="fm.desk"):
            fake.post(StreamDropped(exception=OSError("connection reset")))
            fake.post(FrameUnreadable(command="ERROR", headers={}, body="no such marketplace",
                                      exception=RuntimeError("STOMP ERROR")))
            fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "BUY", 1, 950)], seq=2))
            _await("a delta after both", lambda: desk.book(alpha.id).best_buy_price() == 950)

        assert "WS transport error on marketplace 7: connection reset" in caplog.text
        assert "WS error on marketplace 7: ERROR no such marketplace" in caplog.text
        assert fake.active_reads == 1, "a drop the listener restores is not this layer's to reseed"
    finally:
        desk.close()


def test_a_desk_cancels_through_its_client_on_its_own_marketplace():
    alpha = _market(1, "ALPHA")
    fake, desk = _empty_desk(alpha)
    try:
        cancel = desk.submit_cancel(alpha.id, 101)

        assert fake.cancels == ["7/1 101"]
        assert cancel.original == 101
    finally:
        desk.close()


def test_closing_a_desk_twice_closes_its_stream_once():
    alpha = _market(1, "ALPHA")
    fake, desk = _empty_desk(alpha)

    desk.close()
    desk.close()

    assert fake.stream_closes == 1


def test_a_desk_used_as_a_context_manager_closes_on_leaving_it():
    alpha = _market(1, "ALPHA")
    fake, opened = _empty_desk(alpha)

    with opened as desk:
        assert desk is opened
        assert fake.stream_closes == 0

    assert fake.stream_closes == 1
    with pytest.raises(RuntimeError, match="closed"):
        desk.book(alpha.id)


def test_a_desk_that_has_heard_nothing_for_a_while_still_applies_the_next_delta():
    """The dispatcher wakes every second to see whether the desk has closed.
    Waking to an empty queue is not the end of the stream."""
    alpha = _market(1, "ALPHA")
    fake, desk = _empty_desk(alpha)
    try:
        time.sleep(1.2)
        fake.post(OrdersUpdate(orders=[_limit(alpha, 102, "BUY", 1, 950)], seq=2))

        _await("the delta after a quiet spell", lambda: desk.book(alpha.id).best_buy_price() == 950)
    finally:
        desk.close()
