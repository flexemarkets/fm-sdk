"""What a caller draining ``listen()`` learns when the stream drops.

Java's ``Events`` restores the subscription itself and then puts a
``Reconnected`` on the queue, so a consumer knows its state is stale and
reseeds. Python emitted ``StreamDropped`` and stopped: the stream stayed dead
until someone noticed and called ``fm.reconnect()``, and nothing was ever put
on the queue to say it had come back.

``MarketView`` papered over this by reconnecting on ``StreamDropped`` itself,
so the gap only showed for callers using the raw queue -- which is the whole
point of ``listen()`` being public.

These fail before the auto-reconnect lands: no ``Reconnected`` type exists to
import, and nothing reconnects.
"""

from __future__ import annotations

import queue

import pytest

from fm.events import EventListener, Reconnected, StreamDropped


class _Listener(EventListener):
    """An EventListener with the socket stubbed out.

    Everything below the STOMP frames is what a fake would have to fake, and
    none of it is what these tests are about: they are about what reaches the
    queue after the receive loop dies.
    """

    def __init__(self, event_queue: queue.Queue[object]) -> None:
        super().__init__(
            ws_url="ws://127.0.0.1:0/events",
            bearer_token="t",
            marketplace_id=7,
            event_queue=event_queue,
        )
        self.connects = 0
        self.fail_connects = 0

    def _connect(self):  # type: ignore[override]
        self.connects += 1
        if self.connects <= self.fail_connects:
            raise OSError("refused")
        return object()

    def _stomp_connect(self) -> None:  # type: ignore[override]
        pass

    def _subscribe(self) -> None:  # type: ignore[override]
        pass

    def _receive_loop(self) -> None:  # type: ignore[override]
        pass


def _drain(q: "queue.Queue[object]", timeout: float = 5.0) -> list[object]:
    events: list[object] = []
    deadline_events = 2
    for _ in range(deadline_events):
        try:
            events.append(q.get(timeout=timeout))
        except queue.Empty:
            break
    return events


def test_a_dropped_stream_restores_itself_and_says_so() -> None:
    q: queue.Queue[object] = queue.Queue()
    listener = _Listener(q)
    listener.start()
    before = listener.connects

    listener._on_stream_dropped(OSError("connection reset"))

    events = _drain(q)
    assert isinstance(events[0], StreamDropped)
    assert isinstance(events[1], Reconnected), "the caller is never told the stream came back"
    assert events[1].marketplace_id == 7
    assert listener.connects > before, "nothing reconnected"
    listener.close()


def test_reconnected_carries_the_marketplace_it_belongs_to() -> None:
    """Matching Java's ``Reconnected(long marketplaceId)``.

    One Flexemarkets can hold several subscriptions, so a bare "we are back"
    does not tell a consumer which view to reseed.
    """
    q: queue.Queue[object] = queue.Queue()
    listener = _Listener(q)
    listener.start()

    listener._on_stream_dropped(OSError("connection reset"))

    events = _drain(q)
    assert Reconnected(marketplace_id=7) in events
    listener.close()


def test_a_closed_listener_does_not_reconnect() -> None:
    """close() is a caller saying stop, and a drop follows every close."""
    q: queue.Queue[object] = queue.Queue()
    listener = _Listener(q)
    listener.start()
    listener.close()
    before = listener.connects

    listener._on_stream_dropped(OSError("socket closed"))

    assert listener.connects == before
    with pytest.raises(queue.Empty):
        q.get(timeout=0.5)


def test_one_drop_starts_one_reconnect() -> None:
    """A burst of errors from a dying socket is one outage, not five.

    Java guards with compareAndSet on a `reconnecting` flag; without it a
    handful of failing reads each start their own reconnect thread and the
    caller gets a queue full of Reconnected for a single event.
    """
    q: queue.Queue[object] = queue.Queue()
    listener = _Listener(q)
    listener.start()
    listener.fail_connects = 1  # hold the first attempt open for a retry

    for _ in range(5):
        listener._on_stream_dropped(OSError("connection reset"))

    reconnected = []
    while True:
        try:
            event = q.get(timeout=3.0)
        except queue.Empty:
            break
        if isinstance(event, Reconnected):
            reconnected.append(event)
    assert len(reconnected) == 1, f"expected one Reconnected, got {len(reconnected)}"
    listener.close()
