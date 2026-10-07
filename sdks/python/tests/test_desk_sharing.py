"""``Flexemarkets.desk(id)``, the call every caller makes: one desk and one
stream per marketplace however many handles ask, torn down when the last
handle closes. Mirrors the Java DeskSharingTest case for case.

test_desk drives a ``Desk`` directly, so until this the sharing and the
``DeskHandle`` every caller actually holds ran only in the live-server tests,
which skip without a server. Here the client is real; only sign-in, the API
root and the four calls a desk makes to it answer from the test.
"""

from __future__ import annotations

import queue
import time
from types import SimpleNamespace
from typing import Any, Callable

import pytest

from fm.client import Flexemarkets
from fm.events import OrdersUpdate
from fm.snapshot import Snapshot
from fm.types import Market, Order

MP = 1
ALPHA = 11


def _market(marketplace_id: int) -> Market:
    return Market(id=ALPHA, marketplace_id=marketplace_id, symbol="ALPHA",
                  price_minimum=0, price_maximum=10_000, price_tick=1,
                  unit_minimum=1, unit_maximum=100, unit_tick=1)


def _limit(marketplace_id: int, order_id: int, side: str, units: int, price: int) -> Order:
    return Order(id=order_id, original=order_id, supplier=order_id, consumer=None,
                 type="LIMIT", side=side, units=units, price=price,
                 marketplace_id=marketplace_id, session_id=1,
                 symbol="ALPHA", market_id=ALPHA)


class Scripted(Flexemarkets):
    """A client whose sign-in and desk-facing calls answer from the test."""

    def __init__(self) -> None:
        self.active_reads = 0
        self.unsubscribes = 0
        self.subscribed: list[int] = []
        self.submitted: list[str] = []
        self._streams: dict[int, queue.Queue[object]] = {}
        super().__init__(token="t", endpoint=f"http://127.0.0.1:1/api/marketplaces/{MP}")

    def _sign_in(self, config: dict[str, str]) -> Any:
        return SimpleNamespace(account=SimpleNamespace(id=1, name="dev"),
                               person=SimpleNamespace(id=7), token="t")

    def _fetch_api_root(self) -> Any:
        return {}

    def markets(self, marketplace_id: int) -> list[Market]:  # type: ignore[override]
        return [_market(marketplace_id)]

    def active_orders(self, marketplace_id: int) -> Snapshot:  # type: ignore[override]
        self.active_reads += 1
        return Snapshot(body=[_limit(marketplace_id, 101, "BUY", 5, 1000)], as_of_seq=4)

    def recent_trades(self, marketplace_id: int, *args: Any) -> Snapshot:  # type: ignore[override]
        return Snapshot(body=[], as_of_seq=4)

    def _connect_events(self, marketplace_id: int, q: "queue.Queue[object]") -> Any:  # type: ignore[override]
        self.subscribed.append(marketplace_id)
        self._streams[marketplace_id] = q
        client = self

        class _Events:
            def close(self) -> None:
                client.unsubscribes += 1

        return _Events()

    def submit_limit(self, marketplace_id: int, market_id: int, side: str,  # type: ignore[override]
                     units: int, price: int) -> Order:
        self.submitted.append(f"{marketplace_id}/{market_id} {side} {units}@{price}")
        return _limit(marketplace_id, 900, side, units, price)

    def post(self, marketplace_id: int, event: object) -> None:
        self._streams[marketplace_id].put(event)


def _await(what: str, condition: Callable[[], bool], seconds: float = 5.0) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.002)
    raise AssertionError(f"timed out waiting for: {what}")


@pytest.fixture
def fm() -> Scripted:
    client = Scripted()
    yield client
    client.close()


def test_two_handles_share_one_desk_one_seed_and_one_stream(fm: Scripted) -> None:
    a = fm.desk(MP)
    b = fm.desk(MP)

    assert fm.subscribed == [MP]
    assert fm.active_reads == 1

    # One state behind both: a delta through the one stream reaches each.
    fm.post(MP, OrdersUpdate(orders=[_limit(MP, 102, "BUY", 3, 1100)], seq=5))
    _await("the delta to land", lambda: a.book(ALPHA).best_buy_price() == 1100)
    assert b.book(ALPHA).best_buy_price() == 1100

    a.close()
    b.close()


def test_the_stream_closes_when_the_last_handle_does_and_not_before(fm: Scripted) -> None:
    a = fm.desk(MP)
    b = fm.desk(MP)

    a.close()
    assert fm.unsubscribes == 0, "closed with a handle still open"
    assert b.book(ALPHA).best_buy_price() == 1000

    b.close()
    assert fm.unsubscribes == 1


def test_closing_a_handle_twice_releases_it_once(fm: Scripted) -> None:
    """A second close of one handle must not release the desk another still holds."""
    a = fm.desk(MP)
    b = fm.desk(MP)

    a.close()
    a.close()

    assert fm.unsubscribes == 0
    assert b.book(ALPHA) is not None
    b.close()


def test_a_desk_opened_after_the_last_close_is_a_new_one(fm: Scripted) -> None:
    fm.desk(MP).close()
    with fm.desk(MP) as again:
        assert fm.subscribed == [MP, MP]
        assert fm.active_reads == 2
        assert again.book(ALPHA).best_buy_price() == 1000


def test_each_marketplace_has_its_own_desk(fm: Scripted) -> None:
    with fm.desk(1) as one, fm.desk(2) as two:
        assert sorted(fm.subscribed) == [1, 2]
        assert one.marketplace_id == 1
        assert two.marketplace_id == 2


def test_a_closed_handle_refuses_use_while_the_others_carry_on(fm: Scripted) -> None:
    a = fm.desk(MP)
    b = fm.desk(MP)
    a.close()

    with pytest.raises(RuntimeError, match="closed"):
        a.book(ALPHA)
    with pytest.raises(RuntimeError):
        a.markets
    with pytest.raises(RuntimeError):
        a.on_book_change(ALPHA, lambda book: None)
    with pytest.raises(RuntimeError):
        a.submit_limit(ALPHA, "BUY", 1, 1000)
    assert [m.id for m in b.markets] == [ALPHA]
    b.close()


def test_a_handles_handlers_stop_when_it_closes(fm: Scripted) -> None:
    """A handler registered through a handle stops when that handle closes; the other handle's keeps firing."""
    a = fm.desk(MP)
    b = fm.desk(MP)
    seen_by_a: list[int] = []
    seen_by_b: list[int] = []
    a.on_book_change(ALPHA, lambda book: seen_by_a.append(book.best_buy_price()))
    b.on_book_change(ALPHA, lambda book: seen_by_b.append(book.best_buy_price()))

    a.close()
    fm.post(MP, OrdersUpdate(orders=[_limit(MP, 102, "BUY", 3, 1100)], seq=5))

    _await("b's handler to fire", lambda: 1100 in seen_by_b)
    assert seen_by_a == []
    b.close()


def test_a_handle_reads_and_trades_through_the_shared_desk(fm: Scripted) -> None:
    with fm.desk(MP) as desk:
        assert desk.book(ALPHA).best_buy_price() == 1000
        assert len(desk.books()) == 1
        assert len(desk.tapes()) == 1
        assert desk.tape(ALPHA) is not None

        desk.submit_limit(ALPHA, "SELL", 2, 1500)
        assert fm.submitted == [f"{MP}/{ALPHA} SELL 2@1500"]


def test_closing_the_client_closes_the_desks_its_callers_left_open() -> None:
    client = Scripted()
    client.desk(MP)
    client.desk(2)

    client.close()

    assert client.unsubscribes == 2
