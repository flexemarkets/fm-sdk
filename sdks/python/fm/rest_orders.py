"""Orders over REST, several to a request (fm-sdk#7, docs/SUBMIT-RATE.md, section 5).

What :class:`~fm.order_stream.OrderStream` is for the socket, for a client
connected with ``order_route=OrderRoute.REST``. One request is in flight at a
time; orders sent while it is out wait in a list, and go together as the next
``POST .../orders/batch`` once it is answered. One at a time this is
``submit_limit``; in a burst the requests grow to :data:`MOST_WAITING`.

One request, not several: two lists in flight could reach the book out of
order, which is what this exists to prevent. fm-server runs each list in the
order given, so orders reach the exchange in the order sent.
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from typing import Any

from .exceptions import ConnectionFailedError, FlexemarketsError, OrderOutcomeUnknownError
from .order_stream import PendingOrder
from .types import Order

#: The most orders that wait for the next request; a caller sending more waits
#: for room. The most one request carries, so in a burst every request is full.
MOST_WAITING = 64

#: Posts a list of orders and answers each, in order, with the order it made or
#: the error its refusal raises. Sends once more, whole, when the answer is
#: lost; raises when the request as a whole fails.
Post = Callable[[list[dict[str, Any]]], list["Order | FlexemarketsError"]]


class RestOrderWindow:
    """One marketplace's orders over REST: one request in flight, the rest waiting."""

    def __init__(self, post: Post, marketplace_id: int, most_waiting: int = MOST_WAITING) -> None:
        self._post = post
        self._marketplace_id = marketplace_id
        self._most_waiting = most_waiting
        self._waiting: list[tuple[dict[str, Any], PendingOrder]] = []
        self._sending = False
        self._closed = False
        self._room = threading.Condition()

    def submit(self, order: dict[str, Any]) -> PendingOrder:
        """Queue *order* for the next request, waiting while the list is full."""
        pending = PendingOrder(order["submissionId"])
        with self._room:
            while not self._closed and len(self._waiting) >= self._most_waiting:
                self._room.wait()
            if self._closed:
                raise ConnectionFailedError("The client is closed; the order was not sent.")
            self._waiting.append((order, pending))
            if not self._sending:
                self._sending = True
                threading.Thread(target=self._send, daemon=True,
                                 name=f"fm-rest-orders-{self._marketplace_id}").start()
        return pending

    def close(self) -> None:
        """Refuse what waits as not sent; a request in flight is answered as it lands."""
        with self._room:
            self._closed = True
            waiting, self._waiting = self._waiting, []
            self._room.notify_all()
        for _, pending in waiting:
            pending.set_exception(ConnectionFailedError("The client closed before the order was sent."))

    def _send(self) -> None:
        """Send what waits, a list at a time, until nothing does."""
        while True:
            with self._room:
                if not self._waiting:
                    self._sending = False
                    return
                batch, self._waiting = self._waiting, []
                self._room.notify_all()
            self._answer(batch)

    def _answer(self, batch: list[tuple[dict[str, Any], PendingOrder]]) -> None:
        try:
            outcomes = self._post([order for order, _ in batch])
        except Exception as failed:  # noqa: BLE001 -- however the request failed, each order is told
            # A 4xx is a refusal of the request as a whole -- the token, the
            # route -- before any of it ran, so none was placed: each gets the
            # refusal, as submit_limit would have raised it.
            if isinstance(failed, FlexemarketsError) and 400 <= (getattr(failed, "status_code", None) or 0) < 500:
                for _, pending in batch:
                    pending.set_exception(failed)
                return
            # Anything else -- sent twice without an answer, a 5xx, an answer
            # that cannot be read -- may come after some of the list was
            # placed. Unknown, never refused: a refusal invites placing it
            # again, and if the first had landed, holding the position twice.
            for _, pending in batch:
                pending.set_exception(OrderOutcomeUnknownError(
                    f"No readable answer to the request carrying it: {failed!r}. "
                    "Read the book to learn whether it stands.", pending.submission_id))
            return
        for (_, pending), outcome in zip(batch, outcomes):
            if isinstance(outcome, BaseException):
                pending.set_exception(outcome)
            else:
                pending.set_result(outcome)
