"""An order whose answer is lost is sent again, and trades once.

A send that times out or loses its connection may or may not have reached the
exchange. Reported as a failure, it invited the caller to place the order
again, and if the first had landed the position doubled with nothing anywhere
saying so. Each order now names its submission, and fm-server answers a
submission it already holds with the order it made (its OrderIdempotencyTest),
so the client can send it once more itself.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import httpx
import pytest

from fm.client import Flexemarkets
from fm.exceptions import InvalidArgumentError

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

submitted: list[dict] = []
#: What to do with each order, in turn: "lose" closes the connection without
#: an answer, a status refuses it, anything else accepts it.
plan: list[object] = []


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
        submitted.append(json.loads(raw))
        step = plan.pop(0) if plan else "accept"
        if step == "lose":
            self.close_connection = True
            return
        status = step if isinstance(step, int) else 200
        self._answer(status, {"error": "ORDER_INVALID"} if status != 200 else
                     {"id": 42, "marketplaceId": 1, "marketId": 11, "type": "LIMIT"})

    def _answer(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


@pytest.fixture()
def client():
    submitted.clear()
    plan.clear()
    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    fm = Flexemarkets.connect(TOKEN, f"http://127.0.0.1:{server.server_address[1]}/api/marketplaces/1",
                              "submission-id-test")
    yield fm
    fm.close()
    server.shutdown()


def test_each_order_names_its_own_submission(client):
    client.submit_limit(1, 11, "BUY", 5, 135)
    client.submit_limit(1, 11, "BUY", 5, 135)

    ids = [o["submissionId"] for o in submitted]
    assert all(ids) and ids[0] != ids[1]


def test_an_order_whose_answer_was_lost_is_sent_once_more_as_the_same_submission(client):
    plan.append("lose")

    placed = client.submit_limit(1, 11, "BUY", 5, 135)

    assert placed.id == 42
    assert len(submitted) == 2
    assert submitted[0] == submitted[1], "the same order, the same submission"


def test_a_refused_order_is_not_sent_again(client):
    plan.append(400)

    with pytest.raises(InvalidArgumentError):
        client.submit_limit(1, 11, "BUY", 5, 135)

    assert len(submitted) == 1


def test_an_order_lost_twice_is_reported_and_not_sent_a_third_time(client):
    plan.extend(["lose", "lose"])

    with pytest.raises(httpx.TransportError):
        client.submit_limit(1, 11, "BUY", 5, 135)

    assert len(submitted) == 2


def test_a_connection_is_kept_a_minute_between_requests(client):
    """httpx closes an idle connection after 5 s, so a robot quiet for longer
    paid a new TCP and TLS handshake on its next order -- ~600 ms straight to
    Heroku from Melbourne. A minute, under the 90 s Heroku's router holds one."""
    assert client._http._transport._pool._keepalive_expiry == 60.0
