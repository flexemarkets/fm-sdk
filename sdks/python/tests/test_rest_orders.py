"""send_limit over REST: one request in flight, the rest waiting (fm-sdk#7).

A client connected with ``order_route=OrderRoute.REST`` sends its orders as
``POST .../orders/batch``. One request is in flight at a time, and the orders
sent while it is out go together as the next: one at a time it is
submit_limit, in a burst the requests grow to the window. Two requests in
flight could reach the book out of order, which is what this exists to
prevent, so the first tests hold a request open and look at what queues
behind it.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

from fm import OrderRoute
from fm.client import Flexemarkets
from fm.exceptions import (
    AuthorizationError,
    ConnectionFailedError,
    InvalidArgumentError,
    OrderOutcomeUnknownError,
)
from fm.rest_orders import RestOrderWindow
from fm.types import Order

TIMEOUT = 5.0


class FakePost:
    """A batch POST the test answers by hand: each call waits for release()."""

    def __init__(self) -> None:
        self.batches: list[list[dict]] = []
        self.in_flight = 0
        self.most_in_flight = 0
        self._lock = threading.Lock()
        self._released = threading.Semaphore(0)
        self.arrived = threading.Semaphore(0)
        #: What the next calls do, in turn: an exception to raise, or None to answer.
        self.plan: list[BaseException | None] = []

    def __call__(self, batch: list[dict]) -> list:
        with self._lock:
            self.batches.append(batch)
            self.in_flight += 1
            self.most_in_flight = max(self.most_in_flight, self.in_flight)
        self.arrived.release()
        assert self._released.acquire(timeout=TIMEOUT), "the test never answered"
        with self._lock:
            self.in_flight -= 1
        step = self.plan.pop(0) if self.plan else None
        if step is not None:
            raise step
        return [InvalidArgumentError("refused") if o["price"] == 999 else Order(id=o["price"], price=o["price"])
                for o in batch]

    def release(self, n: int = 1) -> None:
        for _ in range(n):
            self._released.release()

    def wait_for(self, n: int) -> None:
        for _ in range(n):
            assert self.arrived.acquire(timeout=TIMEOUT), f"request {len(self.batches) + 1} never came"


def _order(price: int, submission: str | None = None) -> dict:
    return {"price": price, "submissionId": submission or f"s{price}"}


def test_one_order_goes_alone_and_is_answered():
    post = FakePost()
    window = RestOrderWindow(post, 1)

    pending = window.submit(_order(150))
    post.wait_for(1)
    post.release()

    assert pending.result(TIMEOUT).price == 150
    assert post.batches == [[_order(150)]]


def test_orders_sent_while_a_request_is_out_go_together_as_the_next_in_the_order_sent():
    post = FakePost()
    window = RestOrderWindow(post, 1)

    first = window.submit(_order(100))
    post.wait_for(1)
    behind = [window.submit(_order(101 + i)) for i in range(5)]
    post.release()
    post.wait_for(1)
    post.release()

    assert [[o["price"] for o in batch] for batch in post.batches] == [[100], [101, 102, 103, 104, 105]]
    assert first.result(TIMEOUT).price == 100
    assert [p.result(TIMEOUT).price for p in behind] == [101, 102, 103, 104, 105]
    assert post.most_in_flight == 1, "never two requests in flight"


def test_a_full_list_makes_the_caller_wait_for_room():
    post = FakePost()
    window = RestOrderWindow(post, 1, most_waiting=2)
    window.submit(_order(100))
    post.wait_for(1)
    window.submit(_order(101))
    window.submit(_order(102))

    third = threading.Thread(target=lambda: window.submit(_order(103)))
    third.start()
    third.join(0.2)
    assert third.is_alive(), "a third order waits while two do"

    post.release()          # the first answered; 101 and 102 go, 103 has room
    third.join(TIMEOUT)
    assert not third.is_alive()
    post.wait_for(1)
    post.release()
    post.wait_for(1)
    post.release()

    assert [[o["price"] for o in batch] for batch in post.batches] == [[100], [101, 102], [103]]


def test_a_refused_order_raises_its_refusal_and_its_neighbours_are_placed():
    post = FakePost()
    window = RestOrderWindow(post, 1)
    window.submit(_order(100))
    post.wait_for(1)
    refused = window.submit(_order(999))
    placed = window.submit(_order(101))
    post.release(2)

    with pytest.raises(InvalidArgumentError):
        refused.result(TIMEOUT)
    assert placed.result(TIMEOUT).price == 101


def test_a_refusal_of_the_whole_request_is_each_orders_refusal():
    post = FakePost()
    post.plan.append(_with_status(AuthorizationError("Not permitted"), 403))
    window = RestOrderWindow(post, 1)

    pending = window.submit(_order(100))
    post.wait_for(1)
    post.release()

    with pytest.raises(AuthorizationError):
        pending.result(TIMEOUT)


def _with_status(error, status):
    error.status_code = status
    return error


@pytest.mark.parametrize("failure", [
    httpx.ConnectError("lost twice"),
    _with_status(ConnectionFailedError("Service Unavailable"), 503),
    ValueError("an answer that cannot be read"),
], ids=["lost-twice", "5xx", "unreadable"])
def test_a_request_with_no_readable_answer_leaves_each_order_unknown_never_refused(failure):
    """A 5xx or a lost answer may come after some of the list was placed. A
    refusal would invite placing it again, and holding the position twice."""
    post = FakePost()
    post.plan.append(failure)
    window = RestOrderWindow(post, 1)

    pending = window.submit(_order(100))
    post.wait_for(1)
    post.release()

    with pytest.raises(OrderOutcomeUnknownError) as unknown:
        pending.result(TIMEOUT)
    assert unknown.value.submission_id == "s100"


def test_the_window_carries_on_after_a_failed_request():
    post = FakePost()
    post.plan.append(httpx.ConnectError("lost twice"))
    window = RestOrderWindow(post, 1)
    lost = window.submit(_order(100))
    post.wait_for(1)
    post.release()
    with pytest.raises(OrderOutcomeUnknownError):
        lost.result(TIMEOUT)

    later = window.submit(_order(101))
    post.wait_for(1)
    post.release()
    assert later.result(TIMEOUT).price == 101


def test_closing_refuses_what_waits_as_not_sent_and_answers_what_is_in_flight():
    post = FakePost()
    window = RestOrderWindow(post, 1)
    in_flight = window.submit(_order(100))
    post.wait_for(1)
    waiting = window.submit(_order(101))

    window.close()
    with pytest.raises(ConnectionFailedError):
        waiting.result(TIMEOUT)
    with pytest.raises(ConnectionFailedError):
        window.submit(_order(102))
    post.release()

    assert in_flight.result(TIMEOUT).price == 100
    assert len(post.batches) == 1, "nothing is sent once closed"


# -- through the client ----------------------------------------------------

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"
requests: list[tuple[str, list[dict]]] = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode() if length else "{}"
        if self.path.startswith("/api/v1/tokens"):
            self._answer(200, {"token": TOKEN, "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                               "account": {"id": 1, "name": "dev"}})
            return
        body = json.loads(raw)
        requests.append((self.path, body))
        self._answer(200, [{"status": 201, "order": {"id": 500 + i, "type": o["type"], "price": o.get("price", 0),
                                                      "original": o.get("original", 500 + i),
                                                      "submissionId": o["submissionId"]}}
                           for i, o in enumerate(body)])

    def _answer(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture()
def base_url():
    requests.clear()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}/api/marketplaces/1"
    server.shutdown()


def test_a_rest_client_sends_send_limit_and_send_cancel_as_batches(base_url):
    with Flexemarkets.connect(TOKEN, base_url, "rest-orders-test", order_route=OrderRoute.REST) as fm:
        placed = fm.send_limit(1, 11, "BUY", 1, 150).result(TIMEOUT)
        cancel = fm.send_cancel(1, 11, placed.id).result(TIMEOUT)

    assert placed.price == 150
    assert cancel.type == "CANCEL"
    assert [path for path, _ in requests] == ["/api/v1/marketplaces/1/orders/batch"] * 2
    sent = requests[1][1][0]
    assert sent["type"] == "CANCEL"
    assert sent["id"] == sent["original"] == sent["supplier"] == placed.id, \
        "a REST cancel names its order as a REST CANCEL always has"


def test_the_route_is_named_either_way_and_nothing_else(base_url):
    with Flexemarkets.connect(TOKEN, base_url, "rest-orders-test", order_route="rest") as fm:
        fm.send_limit(1, 11, "BUY", 1, 150).result(TIMEOUT)
    assert len(requests) == 1

    with pytest.raises(InvalidArgumentError):
        Flexemarkets.connect(TOKEN, base_url, "rest-orders-test", order_route="carrier-pigeon")
