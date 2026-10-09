"""What a caller draining ``listen()`` learns when the stream drops.

Java's ``Events`` restores the subscription itself and then puts a
``StreamReconnected`` on the queue, so a consumer knows its state is stale and
reseeds. Python emitted ``StreamDropped`` and stopped: the stream stayed dead
until someone noticed and called ``fm.reconnect()``, and nothing was ever put
on the queue to say it had come back.

``Desk`` papered over this by reconnecting on ``StreamDropped`` itself,
so the gap only showed for callers using the raw queue -- which is the whole
point of ``listen()`` being public.

These fail before the auto-reconnect lands: no ``StreamReconnected`` type exists to
import, and nothing reconnects.
"""

from __future__ import annotations

import queue
import threading

import pytest

from fm.events import EventListener, StreamReconnected, StreamDropped


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
        #: When set, _connect blocks on it. Lets a test hold a reconnect open
        #: for as long as it needs, rather than relying on the retry sleep.
        self.gate: threading.Event | None = None
        #: Fires when a reconnect has reached the gate and is parked there.
        #: Lets a test wait for the window to be open instead of assuming it.
        self.reached_gate = threading.Event()

    def _connect(self):  # type: ignore[override]
        self.connects += 1
        if self.gate is not None:
            self.reached_gate.set()
            if not self.gate.wait(timeout=5.0):
                raise AssertionError("gate never opened")
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
    assert isinstance(events[1], StreamReconnected), "the caller is never told the stream came back"
    assert events[1].marketplace_id == 7
    assert listener.connects > before, "nothing reconnected"
    listener.close()


def test_reconnected_carries_the_marketplace_it_belongs_to() -> None:
    """Matching Java's ``StreamReconnected(long marketplaceId)``.

    One Flexemarkets can hold several subscriptions, so a bare "we are back"
    does not tell a consumer which desk to reseed.
    """
    q: queue.Queue[object] = queue.Queue()
    listener = _Listener(q)
    listener.start()

    listener._on_stream_dropped(OSError("connection reset"))

    events = _drain(q)
    assert StreamReconnected(marketplace_id=7) in events
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
    caller gets a queue full of StreamReconnected for a single event.

    Same shape as Java's oneDropStartsOneReconnect and TypeScript's "one drop
    starts one reconnect": hold the reconnect open, deliver the burst, prove
    the window really was open, then let it finish and count.
    """
    q: queue.Queue[object] = queue.Queue()
    listener = _Listener(q)
    listener.start()

    # Hold the reconnect open, rather than hoping it is slow.
    #
    # This used to say `fail_connects = 1`, meaning "fail the first attempt so
    # the retry sleep holds the lock while the other four drops arrive". It
    # never did that: start() has already spent connect #1, so the attempt
    # inside reconnect() is #2, `2 <= 1` is false, and it succeeds at once.
    # With no failure there is no sleep, the lock is held for microseconds, and
    # whether drops 2-5 are rejected came down to whether a thread outran a
    # five-iteration loop. It lost about one run in five.
    listener.gate = threading.Event()

    for _ in range(5):
        listener._on_stream_dropped(OSError("connection reset"))

    # The premise, asserted rather than assumed.
    #
    # A reconnect must be parked in the gate right now. If it is not, the
    # window was never open and the guard was never exercised -- the test would
    # pass for the wrong reason, which is what the old spelling did until it
    # happened not to. Waiting on reached_gate makes that a deterministic
    # failure rather than a flake: remove the gate and this never fires.
    assert listener.reached_gate.wait(2.0), (
        "no reconnect reached the gate: the burst was delivered after the "
        "reconnect had already finished, so nothing here tests the guard"
    )
    assert _reconnected_so_far(q) == 0, (
        "the reconnect finished before the burst was delivered: this test is "
        "not exercising the single-reconnect guard"
    )

    listener.gate.set()

    reconnected = []
    while True:
        try:
            event = q.get(timeout=3.0)
        except queue.Empty:
            break
        if isinstance(event, StreamReconnected):
            reconnected.append(event)
    assert len(reconnected) == 1, f"expected one StreamReconnected, got {len(reconnected)}"
    listener.close()


def _reconnected_so_far(q: "queue.Queue[object]") -> int:
    """StreamReconnected events already on the queue, without blocking or losing any."""
    drained, count = [], 0
    while True:
        try:
            drained.append(q.get_nowait())
        except queue.Empty:
            break
    for event in drained:
        if isinstance(event, StreamReconnected):
            count += 1
        q.put(event)
    return count


def test_a_failure_that_is_not_a_refusal_is_retried_after_a_pause(monkeypatch) -> None:
    """A server restart or a dropped network is a blip, and riding one out is
    what reconnect() is for -- unlike a refused token, which ends the stream."""
    from fm import events as fm_events

    pauses: list[float] = []
    monkeypatch.setattr(fm_events.time, "sleep", pauses.append)
    q: queue.Queue[object] = queue.Queue()
    listener = _Listener(q)
    listener.start()
    listener.fail_connects = 2  # start() spent the first; the next one fails

    listener.reconnect()

    assert listener.connects == 3, "one failed attempt, then one that succeeded"
    assert pauses == [2]
    assert q.empty(), "a blip is not reported as a refusal"
    listener.close()


def test_a_heartbeat_that_cannot_be_written_stops_the_beating(monkeypatch) -> None:
    """A dead socket is the receive loop's to notice; the heartbeat thread
    just stops rather than writing into it every interval."""
    from fm import events as fm_events

    class _DeadSocket:
        writes = 0

        def send(self, _text: str) -> None:
            _DeadSocket.writes += 1
            raise OSError("broken pipe")

        def close(self) -> None:
            pass

    monkeypatch.setattr(fm_events, "_HEARTBEAT_INTERVAL_SECONDS", 0.01)
    listener = _Listener(queue.Queue())
    listener._ws = _DeadSocket()

    listener._start_heartbeats()
    beating = listener._heartbeat_thread
    beating.join(timeout=2.0)

    assert not beating.is_alive()
    assert _DeadSocket.writes == 1
    listener.close()


def test_heartbeats_stop_once_there_is_no_socket_to_write_to(monkeypatch) -> None:
    from fm import events as fm_events

    monkeypatch.setattr(fm_events, "_HEARTBEAT_INTERVAL_SECONDS", 0.01)
    listener = _Listener(queue.Queue())
    listener._ws = None

    listener._start_heartbeats()
    beating = listener._heartbeat_thread
    beating.join(timeout=2.0)

    assert not beating.is_alive()
