"""The EventListener against a real WebSocket.

Mirrors Java's EventsSocketTest. test_stream_reconnect stubs the connection,
so before this the receive loop, the handshake and what a server closing the
socket does ran in no test -- events.py was at 54% (test risk map, plan
item 7). The server is websockets' own synchronous one, which the client
library already brings.
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from http import HTTPStatus
from typing import Any, Callable

import pytest
from websockets.sync.server import ServerConnection, serve

from fm import events as fm_events
from fm.events import (
    NO_SEQ,
    EventListener,
    FrameUnreadable,
    OrdersUpdate,
    StreamDropped,
    StreamReconnected,
)
from fm.types import Holding

MP = 7


def _message(msg_type: str, body: str, extra_header: str | None = None) -> str:
    return (f"MESSAGE\ndestination:/user/queue/marketplaces/7\nmessage-type:{msg_type}\n"
            + (extra_header + "\n" if extra_header else "") + "\n" + body + "\0")


class _Server:
    """Answers CONNECT with CONNECTED, records what clients send, and lets the
    test speak and hang up on each connection."""

    def __init__(self) -> None:
        self.refuse_with: int | None = None
        self.connect_reply = "CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0"
        self.heartbeats = 0
        self.handshakes = 0
        self.connections: list[ServerConnection] = []
        self.received: list[list[str]] = []
        self.headers: list[dict[str, str]] = []
        self._server = serve(self._handle, "127.0.0.1", 0,
                             process_request=self._process_request,
                             subprotocols=["v12.stomp"])
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    @property
    def url(self) -> str:
        host, port = self._server.socket.getsockname()[:2]
        return f"ws://{host}:{port}/api/events"

    def _process_request(self, connection: ServerConnection, request: Any) -> Any:
        self.handshakes += 1
        if self.refuse_with is not None:
            return connection.respond(HTTPStatus(self.refuse_with), "refused\n")
        self.headers.append({k.lower(): v for k, v in request.headers.raw_items()})
        return None

    def _handle(self, connection: ServerConnection) -> None:
        index = len(self.connections)
        self.received.append([])
        self.connections.append(connection)
        try:
            for message in connection:
                text = message if isinstance(message, str) else message.decode()
                if not text.strip():
                    self.heartbeats += 1
                    continue
                self.received[index].append(text)
                if text.startswith("CONNECT\n"):
                    connection.send(self.connect_reply)
        except Exception:
            pass

    def connection(self, index: int) -> ServerConnection:
        _await(f"connection {index}", lambda: len(self.connections) > index)
        return self.connections[index]

    def close(self) -> None:
        self._server.shutdown()


def _await(what: str, condition: Callable[[], Any], seconds: float = 5.0) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.01)
    raise AssertionError(f"timed out waiting for: {what}")


@pytest.fixture
def server():
    s = _Server()
    yield s
    s.close()


@pytest.fixture
def events():
    return queue.Queue()


@pytest.fixture
def listener(server, events):
    created: list[EventListener] = []

    def start() -> EventListener:
        l = EventListener(server.url, "Bearer the-token", MP, events, "fm-sdk-test")
        l.start()
        created.append(l)
        return l

    yield start
    for l in created:
        l.close()


def _next(events: "queue.Queue[object]") -> object:
    return events.get(timeout=5)


def test_connecting_presents_the_token_and_subscribes_to_the_marketplace(server, listener):
    listener()

    assert server.headers[0]["authorization"] == "Bearer the-token"
    _await("CONNECT and three SUBSCRIBEs", lambda: len(server.received[0]) >= 4)
    connect, *subscribes = server.received[0][:4]
    assert connect.startswith("CONNECT\n")
    assert "agent-description:fm-sdk-test" in connect
    assert "marketplace-id:7" in connect
    assert "destination:/user/queue/marketplaces/7" in subscribes[0]
    assert "destination:/topic/marketplaces/7" in subscribes[1]
    assert "destination:/app/v1/marketplaces/7" in subscribes[2]


def test_an_orders_update_arrives_with_its_sequence_number(server, events, listener):
    listener()
    server.connection(0).send(_message(
        "ORDERS-UPDATE",
        '[{"id":5,"type":"LIMIT","side":"BUY","units":2,"price":300}]', "seq:41"))

    update = _next(events)
    assert isinstance(update, OrdersUpdate)
    assert update.seq == 41
    assert [o.id for o in update.orders] == [5]


def test_an_unreadable_sequence_number_is_no_sequence_number(server, events, listener):
    listener()
    server.connection(0).send(_message("ORDERS-UPDATE", "[]", "seq:not-a-number"))

    assert _next(events).seq == NO_SEQ


def test_a_body_that_spans_lines_is_read_whole(server, events, listener):
    listener()
    server.connection(0).send(_message("HOLDING-UPDATE", '{\n  "cash": 1500,\n  "availableCash": 900\n}'))

    holding = _next(events)
    assert isinstance(holding, Holding)
    assert holding.available_cash == 900


def test_a_stomp_error_is_reported(server, events, listener):
    listener()
    server.connection(0).send("ERROR\nmessage:refused\n\nno such marketplace\0")

    assert isinstance(_next(events), FrameUnreadable)


def test_a_frame_that_cannot_be_read_is_reported_and_the_stream_carries_on(server, events, listener):
    """Reconnecting cannot fix a malformed frame. Java reports it and reads the
    next one; Python dropped a non-JSON body without a word, and treated one
    whose JSON had the wrong shape as a dead stream -- StreamDropped, a full
    reconnect, and a desk reseed, over one bad frame."""
    listener()
    socket = server.connection(0)

    socket.send(_message("HOLDING-UPDATE", "{not json"))
    socket.send(_message("ORDERS-UPDATE", "[5]", "seq:2"))
    socket.send(_message("ORDERS-UPDATE", "[]", "seq:3"))

    first, second, third = _next(events), _next(events), _next(events)
    assert isinstance(first, FrameUnreadable)
    assert isinstance(second, FrameUnreadable)
    assert isinstance(third, OrdersUpdate) and third.seq == 3
    assert server.handshakes == 1, "no reconnect for a bad frame"


def test_frames_reach_the_queue_in_the_order_they_were_sent(server, events, listener):
    listener()
    socket = server.connection(0)
    for seq in range(1, 301):
        socket.send(_message("ORDERS-UPDATE", "[]", f"seq:{seq}"))

    received = [_next(events).seq for _ in range(300)]
    assert received == sorted(received)


def test_a_server_close_is_a_drop_that_reconnects_and_says_so(server, events, listener):
    listener()
    server.connection(0).close()

    assert isinstance(_next(events), StreamDropped)
    assert _next(events) == StreamReconnected(marketplace_id=MP)
    _await("the new socket subscribes again", lambda: len(server.received) > 1 and len(server.received[1]) >= 4)


def test_a_refused_token_on_reconnect_ends_the_stream_and_says_why(server, events, listener):
    listener()
    server.refuse_with = 401
    server.connection(0).close()

    assert isinstance(_next(events), StreamDropped)
    refused = _next(events)
    assert isinstance(refused, StreamDropped)
    assert isinstance(refused.exception, PermissionError)
    with pytest.raises(queue.Empty):
        events.get(timeout=3)
    assert server.handshakes == 2, "one refused attempt, not a retry loop"


def test_the_heartbeats_it_promises_reach_the_server(server, listener, monkeypatch):
    """test_stomp_heartbeat pins the interval; this is the write itself."""
    monkeypatch.setattr(fm_events, "_HEARTBEAT_INTERVAL_SECONDS", 0.02)
    listener()

    _await("two heartbeats", lambda: server.heartbeats >= 2)


def test_a_quiet_stream_is_waited_on_not_dropped(server, events, listener, monkeypatch):
    """The read times out at twice the heartbeat interval. A timeout is the
    server saying nothing, which it may; it is not a dead socket."""
    monkeypatch.setattr(fm_events, "_HEARTBEAT_MS", 50)
    listener()

    time.sleep(0.4)
    server.connection(0).send(_message("ORDERS-UPDATE", "[]", "seq:8"))

    assert _next(events) == OrdersUpdate(orders=[], seq=8)
    assert server.handshakes == 1, "no reconnect for a quiet stream"


def test_an_empty_frame_is_a_heartbeat_not_a_drop(server, events, listener):
    listener()
    socket = server.connection(0)

    socket.send(b"")
    socket.send("\n")
    socket.send(_message("ORDERS-UPDATE", "[]", "seq:9"))

    assert _next(events) == OrdersUpdate(orders=[], seq=9)
    assert server.handshakes == 1


def test_a_connect_answered_with_anything_but_connected_is_logged(server, listener, caplog):
    server.connect_reply = "ERROR\nmessage:bad credentials\n\n\0"

    with caplog.at_level(logging.DEBUG, logger="fm.events"):
        listener()

    assert "STOMP CONNECT reply: ERROR" in caplog.text
