"""A create whose answer is lost is sent again, and makes one thing.

The order path's submissionId (test_submission_id), for everything else a
client creates: a marketplace, a market, a user, an allocation, widgets, a
call. Each is sent under its own ``Idempotency-Key``, and fm-server 4.17
answers a key it already holds with what it answered the first time (its
IdempotencyFilterTest), so the client can send it once more itself.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import httpx
import pytest

from fm.client import Flexemarkets
from fm.exceptions import InvalidArgumentError
from fm.types import Holding, Security, TickGrid

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"
CALL = {"id": 91, "marketplaceId": 1, "marketId": 12, "sessionId": 77, "calledDate": "2026-10-25T09:00:05",
        "trigger": "MANUAL", "price": 550, "volume": 2, "demand": 3, "supply": 2, "buyOrders": 2,
        "sellOrders": 1, "carry": True}

#: Each create as (path, key), the key None when none was sent.
sent: list[tuple[str, str | None]] = []
#: What to do with each create, in turn: "lose" closes the connection without
#: an answer, a status refuses it, anything else accepts it.
plan: list[object] = []


def _answer_to(path: str) -> object:
    if path.endswith("/calls"):
        return CALL
    if path.endswith("/allocations") or path.endswith("/widgets"):
        return []
    if path.endswith("/otp/manager"):
        return {"otps": []}
    return {"id": 5}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path.startswith("/api/v1/tokens"):
            self._answer(200, {"token": TOKEN, "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                               "account": {"id": 1, "name": "dev"}})
            return
        sent.append((self.path, self.headers.get("Idempotency-Key")))
        step = plan.pop(0) if plan else "accept"
        if step == "lose":
            self.close_connection = True
            return
        if isinstance(step, int):
            self._answer(step, {"error": "REQUEST_INVALID", "message": "no"})
            return
        self._answer(201, _answer_to(self.path))

    def _answer(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


@pytest.fixture()
def client():
    sent.clear()
    plan.clear()
    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    fm = Flexemarkets.connect(TOKEN, f"http://127.0.0.1:{server.server_address[1]}/api/marketplaces/1",
                              "idempotency-key-test")
    yield fm
    fm.close()
    server.shutdown()


def test_every_create_names_its_own_key(client, tmp_path):
    csv = tmp_path / "holdings.csv"
    csv.write_text("name,cash\nalice,100\n")
    grid = TickGrid(1, 100, 1)
    holding = Holding(
        marketplace_id=1, owner_id=8, name="alice", cash=10000, available_cash=10000,
        securities=[Security(market_id=10, units=50, available_units=50)],
    )

    client.create_marketplace_from_json('{"name":"mp"}')
    client.create_market(1, "STK", "Stock", grid, grid)
    client.create_call_market(1, "CLL", "Call", grid, grid, 60, True)
    client.create_user("u@x", "pw", "U", "X", "USER")
    client.allocate(1, [holding])
    client.upload_holdings(1, str(csv))
    client.upload_holdings_csv(1, "name,cash\nalice,100\n")
    client.push_widgets(1, [])
    client.clear_now(1, 12)

    assert [path for path, _ in sent] == [
        "/api/v1/marketplaces",
        "/api/v1/marketplaces/1/markets",
        "/api/v1/marketplaces/1/markets",
        "/api/v1/users",
        "/api/v1/marketplaces/1/allocations",
        "/api/v1/marketplaces/1/allocations",
        "/api/v1/marketplaces/1/allocations",
        "/api/v1/marketplaces/1/widgets",
        "/api/v1/marketplaces/1/calls",
    ]
    keys = [key for _, key in sent]
    assert all(keys) and len(set(keys)) == len(keys)


def test_a_post_that_is_not_a_create_carries_no_key(client):
    client.manager_otp_bundle([7])

    assert sent == [("/api/v1/otp/manager", None)]


def test_a_create_whose_answer_was_lost_is_sent_once_more_under_the_same_key(client):
    plan.append("lose")

    assert client.clear_now(1, 12).id == 91

    assert len(sent) == 2
    assert sent[1] == sent[0]


def test_a_csv_create_whose_answer_was_lost_is_sent_once_more_under_the_same_key(client):
    plan.append("lose")

    client.upload_holdings_csv(1, "name,cash\nalice,100\n")

    assert len(sent) == 2
    assert sent[1] == sent[0]


def test_a_refused_create_is_not_sent_again(client):
    plan.append(400)

    with pytest.raises(InvalidArgumentError):
        client.clear_now(1, 12)

    assert len(sent) == 1


def test_a_create_lost_twice_is_reported_and_not_sent_a_third_time(client):
    plan.extend(["lose", "lose"])

    with pytest.raises(httpx.TransportError):
        client.clear_now(1, 12)

    assert len(sent) == 2
