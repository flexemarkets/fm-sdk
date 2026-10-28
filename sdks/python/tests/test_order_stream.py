"""Orders over the socket, several in flight (fm-sdk#7).

The first tests run against a real WebSocket -- test_events_socket's server --
for what only a socket shows: the frames on the wire, in the order sent, and
what a drop and a reconnect do to the orders still unanswered. The rest stub
the listener, to reach what a socket cannot be made to do on cue.
"""

from __future__ import annotations

import json
import queue
import threading
import time
from typing import Any

import pytest

from fm import events as fm_events
from fm.client import Flexemarkets
from fm.events import (
    EventListener,
    OrderAccepted,
    OrderRejected,
    OrdersUpdate,
    StreamDropped,
    StreamReconnected,
    _decode_frame,
)
from fm.exceptions import ConnectionFailedError, InvalidArgumentError, OrderOutcomeUnknownError
from fm.order_stream import OrderStream, PendingOrder
from fm.types import Order
from test_events_socket import MP, _await, _message, _Server

DESTINATION = f"/app/marketplaces/{MP}/orders"


def _order(sid: str, price: int = 900) -> dict[str, Any]:
    return {"marketplaceId": MP, "marketId": 11, "type": "LIMIT", "side": "SELL", "units": 1,
            "price": price, "submissionId": sid}


def _row(sid: str | None, order_id: int) -> dict[str, Any]:
    return {"id": order_id, "original": order_id, "supplier": order_id, "type": "LIMIT", "side": "SELL",
            "units": 1, "price": 900, "mine": True, "submissionId": sid}


def _sent(server: _Server, connection: int) -> list[str]:
    """The submission ids of the orders sent on one connection, in order."""
    frames = [_decode_frame(t) for t in server.received[connection] if t.startswith("SEND\n")]
    assert all(f.headers["destination"] == DESTINATION for f in frames)
    return [json.loads(f.body)["submissionId"] for f in frames]


@pytest.fixture
def server():
    s = _Server()
    yield s
    s.close()


@pytest.fixture
def stream(server):
    made: list[OrderStream] = []

    def open_(**kwargs: Any) -> OrderStream:
        def connect(events: "queue.Queue[object]") -> EventListener:
            listener = EventListener(server.url, "Bearer the-token", MP, events, "fm-sdk-test")
            listener.start()
            return listener
        s = OrderStream(connect, MP, **kwargs)
        made.append(s)
        return s

    yield open_
    for s in made:
        s.close()


class TestOnTheSocket:

    def test_orders_go_out_back_to_back_and_each_is_answered_by_its_own_row(self, server, stream):
        s = stream()
        a, b, c = (s.submit(_order(sid)) for sid in ("s-1", "s-2", "s-3"))
        _await("three orders sent before any answer", lambda: len(_sent(server, 0)) == 3)
        assert _sent(server, 0) == ["s-1", "s-2", "s-3"]
        assert not (a.done() or b.done() or c.done())

        # Answered out of order, among rows that answer nothing of this stream's.
        server.connection(0).send(_message("ORDERS-UPDATE", json.dumps(
            [_row("s-2", 102), _row(None, 99), _row("someone-else", 98), _row("s-1", 101)])))
        assert (a.result(5).id, b.result(5).id) == (101, 102)
        assert not c.done()
        # A row that answers one already answered changes nothing.
        server.connection(0).send(_message("ORDERS-UPDATE", json.dumps([_row("s-1", 555), _row("s-3", 103)])))
        assert (a.result(5).id, c.result(5).id) == (101, 103)

    def test_unanswered_orders_are_sent_again_in_order_once_the_socket_is_back(self, server, stream, monkeypatch):
        monkeypatch.setattr(fm_events, "_reconnect_pause", lambda *_, **__: 0.05)
        s = stream()
        first = s.submit(_order("s-1"))
        _await("the first order sent", lambda: _sent(server, 0) == ["s-1"])

        server.refuse_with = 503
        server.connection(0).close()
        _await("the drop noticed", lambda: not s._up)
        held = s.submit(_order("s-2"))
        time.sleep(0.2)
        assert _sent(server, 0) == ["s-1"] and len(server.connections) == 1

        server.refuse_with = None
        _await("both sent again on the new socket", lambda: len(server.connections) > 1 and len(_sent(server, 1)) == 2)
        assert _sent(server, 1) == ["s-1", "s-2"]
        # The first was placed before the drop: fm-server answers its resend.
        server.connection(1).send(_message("ORDER-ACCEPTED", json.dumps(_row("s-1", 101)), "submission-id:s-1"))
        server.connection(1).send(_message("ORDERS-UPDATE", json.dumps([_row("s-2", 102)])))
        assert (first.result(5).id, held.result(5).id) == (101, 102)

    def test_a_refused_order_raises_what_rest_raises(self, server, stream):
        s = stream()
        pending = s.submit(_order("s-1"))
        _await("sent", lambda: _sent(server, 0) == ["s-1"])
        body = '{"status": "BAD_REQUEST", "error": "ORDER_INVALID", "message": "Price is off the tick."}'
        server.connection(0).send(_message("ORDER-REJECTED", body, "submission-id:s-1"))
        with pytest.raises(InvalidArgumentError, match="off the tick"):
            pending.result(5)


class _Listener:
    """Stands in for the EventListener: records sends, fails them on cue."""

    def __init__(self) -> None:
        self.sent: list[str] = []
        self.fail = 0
        self.closed = False

    def send(self, destination: str, body: str) -> None:
        assert destination == DESTINATION
        if self.fail:
            self.fail -= 1
            raise ConnectionFailedError("The event stream is not connected.")
        self.sent.append(json.loads(body)["submissionId"])

    def close(self) -> None:
        self.closed = True


@pytest.fixture
def stub():
    made: list[OrderStream] = []

    def open_(**kwargs: Any) -> tuple[OrderStream, _Listener]:
        listener = _Listener()
        s = OrderStream(lambda _: listener, MP, **kwargs)  # type: ignore[arg-type,return-value]
        made.append(s)
        return s, listener

    yield open_
    for s in made:
        s.close()


def _settle(s: OrderStream) -> None:
    """Wait until the dispatcher has handled everything put before this."""
    done = threading.Event()
    real = s._answer

    def answer(sid: str, outcome: Any) -> None:
        if sid == "__settle__":
            done.set()
        else:
            real(sid, outcome)

    s._answer = answer  # type: ignore[method-assign]
    s._events.put(OrdersUpdate(orders=[Order(submission_id="__settle__")], seq=0))
    assert done.wait(5)
    s._answer = real  # type: ignore[method-assign]


class TestStubbed:

    def test_the_window_holds_the_next_order_until_one_is_answered(self, stub):
        s, listener = stub(max_in_flight=2)
        first = s.submit(_order("s-1"))
        s.submit(_order("s-2"))
        third: list[PendingOrder] = []
        waiting = threading.Thread(target=lambda: third.append(s.submit(_order("s-3"))))
        waiting.start()
        time.sleep(0.2)
        assert listener.sent == ["s-1", "s-2"] and not third

        s._events.put(OrdersUpdate(orders=[Order(id=101, submission_id="s-1")], seq=1))
        waiting.join(5)
        assert first.result(5).id == 101
        assert listener.sent == ["s-1", "s-2", "s-3"] and third[0].submission_id == "s-3"

    def test_a_send_that_fails_is_held_and_sent_again_on_reconnect(self, stub):
        s, listener = stub()
        listener.fail = 1
        s.submit(_order("s-1"))
        s.submit(_order("s-2"))
        assert listener.sent == [] and not s._up

        # The first resend fails as well: the rest wait for the next reconnect.
        listener.fail = 1
        s._events.put(StreamReconnected(marketplace_id=MP))
        _settle(s)
        assert listener.sent == []
        s._events.put(StreamReconnected(marketplace_id=MP))
        _settle(s)
        assert listener.sent == ["s-1", "s-2"]

    def test_answers_that_name_no_submission_answer_nothing(self, stub):
        s, _ = stub()
        pending = s.submit(_order("s-1"))
        s._events.put(OrderAccepted(submission_id=None, order=Order(id=1)))
        s._events.put(OrderRejected(submission_id=None, error=InvalidArgumentError("x")))
        s._events.put("a frame this stream does not read")
        _settle(s)
        assert not pending.done()
        s._events.put(OrderRejected(submission_id="s-1", error=InvalidArgumentError("refused")))
        with pytest.raises(InvalidArgumentError, match="refused"):
            pending.result(5)

    def test_closing_fails_what_is_unanswered_by_whether_it_was_sent(self, stub):
        s, listener = stub()
        sent = s.submit(_order("s-1"))
        s._events.put(StreamDropped(exception=OSError("gone")))
        _settle(s)
        held = s.submit(_order("s-2"))

        s.close()
        s.close()
        assert listener.closed
        with pytest.raises(OrderOutcomeUnknownError, match="may have been placed") as unknown:
            sent.result(5)
        assert unknown.value.submission_id == "s-1"
        with pytest.raises(ConnectionFailedError, match="was not sent"):
            held.result(5)
        # Closed: refused at once, and the window it took is given back.
        for _ in range(3):
            with pytest.raises(ConnectionFailedError, match="closed"):
                s.submit(_order("s-3"))
        assert s._window.acquire(blocking=False)

    def test_a_refused_token_closes_the_stream_and_says_why(self, stub):
        s, listener = stub()
        pending = s.submit(_order("s-1"))
        refusal = PermissionError("WebSocket refused: the token was rejected.")
        s._events.put(StreamDropped(exception=refusal))
        with pytest.raises(OrderOutcomeUnknownError) as unknown:
            pending.result(5)
        assert unknown.value.__cause__ is refusal
        assert listener.closed
        s._dispatcher.join(5)
        assert not s._dispatcher.is_alive()


class TestFromTheClient:

    def test_send_limit_goes_on_the_marketplace_socket_and_close_ends_it(self, server, monkeypatch):
        from test_submission_id import TOKEN, Handler
        from http.server import HTTPServer

        http = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=http.serve_forever, daemon=True).start()
        fm = Flexemarkets.connect(TOKEN, f"http://127.0.0.1:{http.server_address[1]}/api/marketplaces/1",
                                  "order-stream-test")
        opened: list[EventListener] = []

        def connect(marketplace_id: int, events: "queue.Queue[object]", history: bool = False,
                    reconnect_max_seconds: float | None = None) -> EventListener:
            # An order socket comes back within five seconds; a subscription waits up to a minute.
            assert (marketplace_id, history, reconnect_max_seconds) == (MP, False, 5.0)
            listener = EventListener(server.url, "Bearer the-token", MP, events, "fm-sdk-test")
            listener.start()
            opened.append(listener)
            return listener

        monkeypatch.setattr(fm, "_connect_events", connect)
        try:
            first = fm.send_limit(MP, 11, "SELL", 1, 900)
            second = fm.send_limit(MP, 11, "SELL", 1, 901)
            assert isinstance(first, PendingOrder) and len(opened) == 1
            _await("both sent", lambda: len(_sent(server, 0)) == 2)
            assert _sent(server, 0) == [first.submission_id, second.submission_id]
            sent = [json.loads(_decode_frame(t).body) for t in server.received[0] if t.startswith("SEND\n")]
            assert (sent[1]["price"], sent[1]["clientDescription"]) == (901, "order-stream-test")
        finally:
            fm.close()
            http.shutdown()
        with pytest.raises(OrderOutcomeUnknownError):
            first.result(5)
        assert fm._order_streams == {}
