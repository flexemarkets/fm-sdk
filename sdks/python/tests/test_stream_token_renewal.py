"""The event stream renews an expired token once, as the client's requests do.

A stream that reconnected after the 24-hour token ran out was refused at the
handshake and ended -- right, for a token that will never work, and wrong for
one the client can renew with the credentials it holds. Now a refused
reconnect asks the client to renew, and tries once more with the new token.
"""

from __future__ import annotations

import queue

from fm.events import EventListener, StreamDropped, StreamReconnected


class _Refused(Exception):
    def __init__(self, status: int) -> None:
        super().__init__(f"HTTP {status}")
        self.response = type("Response", (), {"status_code": status})()


class _Listener(EventListener):
    """The socket stubbed out; the server accepts only the token in ``accepted``."""

    def __init__(self, q: "queue.Queue[object]", token: dict[str, str], renew) -> None:
        super().__init__(ws_url="ws://127.0.0.1:0/events", bearer_token=lambda: token["bearer"],
                         marketplace_id=7, event_queue=q, renew=renew)
        self.presented: list[str] = []
        self.accepted = token["bearer"]

    def _connect(self):  # type: ignore[override]
        bearer = self._present()
        self.presented.append(bearer)
        if bearer != self.accepted:
            raise _Refused(401)
        return object()

    def _stomp_connect(self) -> None:  # type: ignore[override]
        pass

    def _subscribe(self) -> None:  # type: ignore[override]
        pass

    def _receive_loop(self) -> None:  # type: ignore[override]
        pass


def _drain(q: "queue.Queue[object]", n: int = 2) -> list[object]:
    return [q.get(timeout=5.0) for _ in range(n)]


def test_a_refused_reconnect_renews_the_token_and_comes_back() -> None:
    q: queue.Queue[object] = queue.Queue()
    token = {"bearer": "Bearer old"}

    refused_with: list[str] = []

    def renew(presented: str) -> bool:
        refused_with.append(presented)
        token["bearer"] = "Bearer new"
        return True

    listener = _Listener(q, token, renew)
    listener.start()
    listener.accepted = "Bearer new"   # the old token has expired since

    listener._on_stream_dropped(OSError("connection reset"))

    events = _drain(q)
    assert isinstance(events[0], StreamDropped)
    assert isinstance(events[1], StreamReconnected)
    assert listener.presented == ["Bearer old", "Bearer old", "Bearer new"]
    assert refused_with == ["Bearer old"], "the stream says which token was refused"
    listener.close()


def test_a_refused_reconnect_that_cannot_renew_ends_the_stream_as_before() -> None:
    q: queue.Queue[object] = queue.Queue()
    token = {"bearer": "Bearer old"}
    listener = _Listener(q, token, renew=lambda presented: False)
    listener.start()
    listener.accepted = "Bearer new"

    listener._on_stream_dropped(OSError("connection reset"))

    events = _drain(q)
    assert isinstance(events[1], StreamDropped)
    assert isinstance(events[1].exception, PermissionError)
    assert listener.presented == ["Bearer old", "Bearer old"]
