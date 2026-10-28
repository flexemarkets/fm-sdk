"""Orders over the socket, several in flight (fm-sdk#7, docs/SUBMIT-RATE.md).

A REST order waits for its answer before the next can go, so a robot's order
rate is one over the round trip. fm-server keeps the orders one socket sends
in the order it sent them (its SocketOrderSequence), so a client may send the
next before the last is answered and still have them matched in sequence.
That is what this does: each order goes out as a STOMP SEND at once, and its
answer comes back on the stream -- the ORDERS-UPDATE fan-out for an order
placed, ORDER-REJECTED for one refused, ORDER-ACCEPTED for a resend of one
already placed -- matched to it by the submission id it was sent with.
"""

from __future__ import annotations

import json
import queue
import threading
import time
from collections.abc import Callable
from concurrent.futures import Future
from dataclasses import dataclass
from typing import Any

from .events import (
    EventListener,
    OrderAccepted,
    OrderRejected,
    OrdersUpdate,
    StreamDropped,
    StreamReconnected,
)
from .exceptions import ConnectionFailedError, OrderOutcomeUnknownError
from .types import Order

# How many orders may be unanswered at once before submit() waits for one.
# Bounds what a burst puts in front of the server and on the user's 4 MB
# per-session send buffer, where the answers and the marketplace's fan-out
# queue together. A first guess; fm-server's W3 load run should set it.
MAX_IN_FLIGHT = 64

# The longest an order socket waits between attempts to come back: five
# seconds, where a subscription waits up to a minute. Orders are waiting on it,
# and every second it waits is a second they sit unsent -- an outage ran 24-33 s
# past the server's return on the minute's back-off. It still doubles from 2 s
# and is drawn from the upper half, so a classroom's robots do not come back in
# step; and an order socket asks for no history, which is what the minute
# spares the server.
RECONNECT_MAX_SECONDS = 5.0

# The longest an order waits, unsent, for the order socket to come back. Long
# enough for a reconnect after a blip (the socket retries within RECONNECT_MAX_SECONDS);
# after that the order is refused as not sent, as REST refused it with a 503
# during an outage. Held for the whole outage instead, a robot's quotes went
# onto the book up to a minute and a half after it sent them -- stale, and on
# top of the ones it sent again once it saw the stream come back.
HOLD_SECONDS = 5.0

_STOP = object()
_WAKE = object()


class PendingOrder(Future):  # type: ignore[type-arg]
    """An order sent over the socket, not yet answered.

    A :class:`concurrent.futures.Future`: ``result()`` waits for and returns the
    :class:`~fm.types.Order` fm-server made, or raises what REST would have
    raised for its refusal -- or :class:`~fm.exceptions.OrderOutcomeUnknownError`
    if the stream closed before the answer came. An asyncio caller awaits
    ``asyncio.wrap_future(pending)``.
    """

    def __init__(self, submission_id: str) -> None:
        super().__init__()
        self.submission_id = submission_id


@dataclass
class _Unanswered:
    future: PendingOrder
    body: str
    # Whether a send of it was attempted. One that never was cannot have been
    # placed, and is told so when the stream closes instead of "unknown".
    sent: bool = False
    # When it began waiting, unsent, for the socket (time.monotonic()); None
    # once sent, or while the socket is up.
    held_since: float | None = None


class OrderStream:
    """One marketplace's order socket: sends, matches answers, resends.

    While the socket is down an order is held rather than sent another way,
    and every unanswered order is sent again, in the order first submitted,
    once it is back. Sending the held ones over REST meanwhile would let them
    overtake orders sent before the drop, and in-order is the point. An order
    that has not gone out at all is held for at most :data:`HOLD_SECONDS`, then
    refused as not sent; one sent before the drop may have been placed, and is
    sent again whenever the socket returns.
    """

    def __init__(
        self,
        connect: Callable[["queue.Queue[object]"], EventListener],
        marketplace_id: int,
        max_in_flight: int = MAX_IN_FLIGHT,
        hold_seconds: float = HOLD_SECONDS,
    ) -> None:
        self._hold_seconds = hold_seconds
        self._destination = f"/app/marketplaces/{marketplace_id}/orders"
        self._events: queue.Queue[object] = queue.Queue()
        self._window = threading.BoundedSemaphore(max_in_flight)
        # Taken around every send and every change to what is unanswered, so a
        # resend after a reconnect and a new submit cannot interleave.
        self._lock = threading.Lock()
        self._unanswered: dict[str, _Unanswered] = {}
        self._closed = False
        self._listener = connect(self._events)
        self._up = True
        self._dispatcher = threading.Thread(target=self._dispatch, name="fm-order-stream", daemon=True)
        self._dispatcher.start()

    def submit(self, order: dict[str, Any]) -> PendingOrder:
        """Send *order*, which names its ``submissionId``, and return at once.

        Waits only while :data:`MAX_IN_FLIGHT` orders are unanswered. Raises
        :class:`~fm.exceptions.ConnectionFailedError` once the stream is closed.
        """
        submission_id = order["submissionId"]
        self._window.acquire()
        with self._lock:
            if self._closed:
                self._window.release()
                raise ConnectionFailedError("The order stream is closed; the order was not sent.")
            pending = PendingOrder(submission_id)
            pending.add_done_callback(lambda _: self._window.release())
            entry = _Unanswered(pending, json.dumps(order))
            self._unanswered[submission_id] = entry
            if self._up:
                self._send(entry)
            else:
                entry.held_since = time.monotonic()
                self._events.put(_WAKE)  # so the dispatcher times its hold
        return pending

    def close(self, cause: BaseException | None = None) -> None:
        """Stop, and fail every unanswered order: one that was sent with
        :class:`~fm.exceptions.OrderOutcomeUnknownError`, one that was not with
        :class:`~fm.exceptions.ConnectionFailedError`."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            left, self._unanswered = list(self._unanswered.values()), {}
        self._listener.close()
        self._events.put(_STOP)
        for entry in left:
            if entry.sent:
                error: Exception = OrderOutcomeUnknownError(
                    f"Order {entry.future.submission_id} was sent and its stream closed before the answer; "
                    "it may have been placed.", entry.future.submission_id)
            else:
                error = ConnectionFailedError(
                    f"Order {entry.future.submission_id} was not sent: its stream closed first.")
            error.__cause__ = cause
            entry.future.set_exception(error)

    # -- the dispatcher thread ---------------------------------------------

    def _send(self, entry: _Unanswered) -> None:
        """Send one, under the lock. A failure leaves it held for the reconnect."""
        entry.sent = True
        entry.held_since = None
        try:
            self._listener.send(self._destination, entry.body)
        except ConnectionFailedError:
            self._up = False

    def _dispatch(self) -> None:
        while True:
            try:
                event = self._events.get(timeout=self._until_a_hold_ends())
            except queue.Empty:
                event = _WAKE
            self._refuse_long_held()
            if event is _STOP:
                return
            if isinstance(event, OrdersUpdate):
                for order in event.orders:
                    if order.submission_id is not None:
                        self._answer(order.submission_id, order)
            elif isinstance(event, OrderAccepted):
                if event.submission_id is not None:
                    self._answer(event.submission_id, event.order)
            elif isinstance(event, OrderRejected):
                if event.submission_id is not None:
                    self._answer(event.submission_id, event.error)
            elif isinstance(event, StreamDropped):
                if isinstance(event.exception, PermissionError):
                    # The listener has given up: its token was refused twice.
                    self.close(event.exception)
                    return
                with self._lock:
                    self._up = False
            elif isinstance(event, StreamReconnected):
                with self._lock:
                    self._up = True
                    for entry in list(self._unanswered.values()):
                        if not self._up:
                            break
                        self._send(entry)

    def _until_a_hold_ends(self) -> float | None:
        """Seconds until the longest-held unsent order has waited its limit; None if none is held."""
        with self._lock:
            since = [e.held_since for e in self._unanswered.values() if e.held_since is not None]
        if not since:
            return None
        return max(0.0, min(since) + self._hold_seconds - time.monotonic())

    def _refuse_long_held(self) -> None:
        """Refuse, as not sent, every order that has waited unsent for the socket longer than the hold."""
        now = time.monotonic()
        with self._lock:
            expired = [e for e in self._unanswered.values()
                       if e.held_since is not None and now - e.held_since >= self._hold_seconds]
            for entry in expired:
                del self._unanswered[entry.future.submission_id]
        # Outside the lock: a done-callback may submit again.
        for entry in expired:
            entry.future.set_exception(ConnectionFailedError(
                f"Order {entry.future.submission_id} was not sent: the order socket was down "
                f"for more than {self._hold_seconds:g} s."))

    def _answer(self, submission_id: str, outcome: "Order | Exception") -> None:
        with self._lock:
            entry = self._unanswered.pop(submission_id, None)
        # Outside the lock: a done-callback may submit again.
        if entry is None:
            return
        if isinstance(outcome, Exception):
            entry.future.set_exception(outcome)
        else:
            entry.future.set_result(outcome)
