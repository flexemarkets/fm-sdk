"""Several limit orders in one request: ``POST .../orders/batch`` (fm-sdk#7).

REST one order at a time is bounded by the round trip, and several requests at
once reach the book out of order. A list is ordered by construction; fm-server
runs it in list order and answers each order as its own POST would have. Here
the answer is checked to come back to the caller as :meth:`submit_limit`'s
would: the order, or the error its refusal raises.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import httpx
import pytest

from fm import Limit
from fm.client import MOST_BATCHED, Flexemarkets
from fm.exceptions import ApiError, AuthorizationError, ConflictError, InvalidArgumentError

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

#: Each batch request, as (path, body).
requests: list[tuple[str, list[dict]]] = []
#: What to do with each request, in turn: "lose" closes the connection
#: without an answer, a status refuses the whole request, anything else
#: answers each order by its price -- see _entry.
plan: list[object] = []


def _entry(order: dict) -> dict:
    """An order's answer by its price: 999 is refused for assets, 998 is
    already cancelled, anything else placed."""
    if order["price"] == 999:
        return {"status": 400, "failure": {"status": "BAD_REQUEST", "error": "ORDER_INSUFFICIENT_ASSETS",
                                           "message": "Insufficient assets."}}
    if order["price"] == 998:
        return {"status": 409, "failure": {"status": "CONFLICT", "error": "ORDER_ALREADY_CANCELLED",
                                           "message": "Already cancelled."}}
    return {"status": 201, "order": {"id": 1000 + len(placed), "marketplaceId": 1, "marketId": order["marketId"],
                                     "type": "LIMIT", "side": order["side"], "units": order["units"],
                                     "price": order["price"], "submissionId": order["submissionId"]}}


placed: list[dict] = []


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
        step = plan.pop(0) if plan else "answer"
        if step == "lose":
            self.close_connection = True
            return
        if step == "short":
            self._answer(200, [_entry(order) for order in body][1:])
            return
        if isinstance(step, int):
            self._answer(step, {"status": "FORBIDDEN", "error": "ACCESS_DENIED", "message": "No."})
            return
        answers = []
        for order in body:
            entry = _entry(order)
            if "order" in entry:
                placed.append(order)
            answers.append(entry)
        self._answer(200, answers)

    def _answer(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


@pytest.fixture()
def client():
    requests.clear()
    plan.clear()
    placed.clear()
    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    fm = Flexemarkets.connect(TOKEN, f"http://127.0.0.1:{server.server_address[1]}/api/marketplaces/1",
                              "submit-limits-test")
    yield fm
    fm.close()
    server.shutdown()


def test_the_orders_go_in_one_request_in_order_and_come_back_in_it(client):
    answered = client.submit_limits(1, [Limit(11, "BUY", 1, 150), Limit(11, "SELL", 2, 160),
                                        Limit(12, "BUY", 3, 170, client_description="mine")])

    assert len(requests) == 1
    path, body = requests[0]
    assert path == "/api/v1/marketplaces/1/orders/batch"
    assert [o["price"] for o in body] == [150, 160, 170]
    assert [o["marketId"] for o in body] == [11, 11, 12]
    assert body[2]["clientDescription"] == "mine"
    assert body[0]["clientDescription"] == "submit-limits-test"
    ids = [o["submissionId"] for o in body]
    assert all(ids) and len(set(ids)) == 3, "every order names its own submission"

    orders = [p.result() for p in answered]
    assert [o.price for o in orders] == [150, 160, 170]
    assert [p.submission_id for p in answered] == ids


def test_a_refused_order_raises_what_submit_limit_raises_and_the_rest_are_placed(client):
    answered = client.submit_limits(1, [Limit(11, "BUY", 1, 150), Limit(11, "BUY", 1, 999),
                                        Limit(11, "BUY", 1, 998), Limit(11, "BUY", 1, 151)])

    assert answered[0].result().price == 150
    with pytest.raises(InvalidArgumentError) as refused:
        answered[1].result()
    assert refused.value.status_code == 400
    assert refused.value.reason == "ORDER_INSUFFICIENT_ASSETS"
    assert "Insufficient assets." in str(refused.value)
    with pytest.raises(ConflictError) as conflict:
        answered[2].result()
    assert conflict.value.reason == "ORDER_ALREADY_CANCELLED"
    assert answered[3].result().price == 151


def test_a_long_list_goes_as_several_requests_one_after_the_other(client):
    limits = [Limit(11, "BUY", 1, 100 + i) for i in range(MOST_BATCHED * 2 + 1)]

    answered = client.submit_limits(1, limits)

    assert [len(body) for _, body in requests] == [MOST_BATCHED, MOST_BATCHED, 1]
    assert [o["price"] for _, body in requests for o in body] == [limit.price for limit in limits]
    assert [p.result().price for p in answered] == [limit.price for limit in limits]


def test_nothing_to_place_sends_nothing(client):
    assert client.submit_limits(1, []) == []
    assert requests == []


def test_a_request_whose_answer_was_lost_is_sent_once_more_whole(client):
    plan.append("lose")

    answered = client.submit_limits(1, [Limit(11, "BUY", 1, 150), Limit(11, "BUY", 1, 151)])

    assert len(requests) == 2
    assert requests[0][1] == requests[1][1], "the same orders, the same submissions"
    assert [p.result().price for p in answered] == [150, 151]


def test_a_request_lost_twice_is_raised(client):
    plan.extend(["lose", "lose"])

    with pytest.raises(httpx.TransportError):
        client.submit_limits(1, [Limit(11, "BUY", 1, 150)])


def test_a_refusal_of_the_whole_request_is_raised(client):
    plan.append(403)

    with pytest.raises(AuthorizationError):
        client.submit_limits(1, [Limit(11, "BUY", 1, 150)])
    assert len(requests) == 1, "a refusal is not sent again"


def test_both_counterparty_names_are_refused_before_anything_is_sent(client):
    with pytest.raises(InvalidArgumentError):
        client.submit_limits(1, [Limit(11, "BUY", 1, 150),
                                 Limit(11, "BUY", 1, 150, owner_target="a", owner_target_id=3)])
    assert requests == []


def test_an_answer_without_one_entry_per_order_is_an_api_error(client):
    plan.append("short")

    with pytest.raises(ApiError):
        client.submit_limits(1, [Limit(11, "BUY", 1, 150), Limit(11, "BUY", 1, 151)])
