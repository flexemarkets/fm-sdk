"""The snapshot routes return orders inside a HAL envelope, under "orders".

Every SDK read ``_embedded.orderDtoes`` -- Spring HATEOAS pluralising the
server's old ``OrderDto`` -- long after the server started sending
``_embedded.orders``. So ``active_orders`` and ``recent_trades`` returned an
empty list always, in all three SDKs, for their whole life.

That is not a cosmetic miss. ``MarketView`` seeds its books from
``active_orders``: the seed was always empty, and the books filled from live
deltas afterwards, which looks plausible until you open a view on a marketplace
that already has resting orders and see nothing.

Nothing caught it because nothing tested it -- the only mention of
``active_orders`` in any suite was a stub in a fake returning None.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets, _embedded_orders

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

# Sampled from a running fm-server: GET /api/v1/marketplaces/{id}/orders/active
ENVELOPE = {
    "_embedded": {
        "orders": [{
            "id": 80035520, "original": 80035520, "supplier": 80035520,
            "consumer": None, "type": "LIMIT", "side": "BUY",
            "symbol": "STK", "units": 5, "price": 125, "marketId": 6560,
        }]
    }
}


class Handler(BaseHTTPRequestHandler):
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

    def do_GET(self):
        if self.path == "/api/tokens/refresh":
            self._send({"token": TOKEN,
                        "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                        "account": {"id": 1, "name": "dev"}})
        elif "/orders/active" in self.path or "/orders/recent-trades" in self.path:
            self._send(ENVELOPE)
        elif self.path == "/api":
            self._send({"_links": {}})
        else:
            self._send([])


@pytest.fixture
def client():
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}/api"
    fm = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "envelope-test")
    yield fm
    fm.close()
    httpd.shutdown()


def test_active_orders_reads_the_envelope(client):
    snapshot = client.active_orders(1)

    assert len(snapshot.body) == 1, "the order the server sent, not an empty list"
    assert snapshot.body[0].price == 125
    assert snapshot.as_of_seq == 7


def test_recent_trades_reads_the_envelope(client):
    assert len(client.recent_trades(1).body) == 1


def test_the_old_spelling_is_still_accepted():
    """An older server sends orderDtoes; it should still parse."""
    assert len(_embedded_orders({"_embedded": {"orderDtoes": [{"id": 1}]}})) == 1
    assert len(_embedded_orders({"_embedded": {"orders": [{"id": 1}]}})) == 1


def test_an_empty_or_absent_envelope_is_an_empty_list():
    assert _embedded_orders({}) == []
    assert _embedded_orders({"_embedded": {}}) == []
    assert _embedded_orders({"_embedded": {"orders": None}}) == []
