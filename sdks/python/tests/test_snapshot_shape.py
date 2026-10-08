"""The snapshot reads accept a bare array and nothing else.

``active_orders`` and ``recent_trades`` call
``GET /api/v1/marketplaces/{id}/orders``, which has only ever answered a bare
array. Earlier SDKs also read HAL envelopes, and answered an empty list for any
shape they did not recognise -- which is how ``Desk`` once seeded empty books
for months and looked plausible. So any other shape is now an ``ApiError``, not
an empty snapshot.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets, _orders_of
from fm.exceptions import ApiError

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

ORDER = {
    "id": 80035520, "original": 80035520, "supplier": 80035520,
    "consumer": None, "type": "LIMIT", "side": "BUY",
    "symbol": "STK", "units": 5, "price": 125, "marketId": 6560,
}

# What the V1 route sends.
BARE_ARRAY = [ORDER]

# The HAL envelope older routes sent; the V1 route never has.
ENVELOPE = {"_embedded": {"orders": [ORDER]}}


class Handler(BaseHTTPRequestHandler):
    snapshot: object = BARE_ARRAY

    def log_message(self, *args):
        pass

    def _send(self, payload, seq="7"):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("x-fm-as-of-seq", seq)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path == "/api/tokens/refresh":
            self._send({"token": TOKEN,
                        "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                        "account": {"id": 1, "name": "dev"}})
        else:
            self._send([])

    def do_GET(self):
        if self.path.startswith("/api/v1/marketplaces/1/orders?state="):
            self._send(type(self).snapshot)
        else:
            self._send([])


def _serving(snapshot):
    return type("Serving", (Handler,), {"snapshot": snapshot})


@pytest.fixture
def connect():
    servers = []
    clients = []

    def _connect(snapshot=BARE_ARRAY):
        httpd = HTTPServer(("127.0.0.1", 0), _serving(snapshot))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        servers.append(httpd)
        base = f"http://127.0.0.1:{httpd.server_address[1]}/api"
        fm = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "snapshot-shape-test")
        clients.append(fm)
        return fm

    yield _connect
    for fm in clients:
        fm.close()
    for httpd in servers:
        httpd.shutdown()


def test_active_orders_reads_the_bare_array(connect):
    snapshot = connect().active_orders(1)

    assert len(snapshot.body) == 1, "the order the server sent, not an empty list"
    assert snapshot.body[0].price == 125
    assert snapshot.as_of_seq == 7


def test_recent_trades_reads_the_bare_array(connect):
    snapshot = connect().recent_trades(1)

    assert len(snapshot.body) == 1
    assert snapshot.as_of_seq == 7


def test_an_envelope_is_an_api_error(connect):
    fm = connect(ENVELOPE)

    with pytest.raises(ApiError, match="not a list of orders"):
        fm.active_orders(1)
    with pytest.raises(ApiError, match="not a list of orders"):
        fm.recent_trades(1)


@pytest.mark.parametrize("body", [{}, None, "orders", 42])
def test_a_body_that_is_not_a_list_is_an_api_error(connect, body):
    with pytest.raises(ApiError, match="not a list of orders"):
        connect(body).active_orders(1)


@pytest.mark.parametrize("body", [{"_embedded": {"orderDtoes": [ORDER]}}, {}, None, "x"])
def test_orders_of_refuses_anything_but_a_list(body):
    with pytest.raises(ApiError, match="not a list of orders"):
        _orders_of(body)
