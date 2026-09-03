"""A market order, on an exchange that has none.

The server's type switch falls through to ``LIMIT``, so every submission is
bounds-checked against the market and must sit on a tick. Java's version sent
``Long.MAX_VALUE`` to buy and ``0`` to sell -- prices no real market accepts --
and Python and TypeScript had no version at all.

Ported once the semantics were settled: cross the book at the extreme legal
price, then cancel the remainder, so a market order that does not fill cannot
be left resting at the best price in the book.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets, _marketable_limit
from fm.exceptions import InvalidArgumentError
from fm.types import Market

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

submitted: list[dict] = []

# price_minimum 110, tick 25 -> the legal prices are 110, 135, 160, 185.
MARKETS = [{
    "id": 11, "marketplaceId": 1, "symbol": "STK", "name": "Stock",
    "priceMinimum": 110, "priceMaximum": 199, "priceTick": 25,
    "unitMinimum": 1, "unitMaximum": 100, "unitTick": 1,
}]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/tokens/refresh":
            self._send({
                "token": TOKEN,
                "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                "account": {"id": 1, "name": "dev"},
            })
            return
        if self.path == "/api":
            base = f"http://127.0.0.1:{self.server.server_address[1]}/api"
            self._send({"_links": {
                "marketplaces": {"href": f"{base}/marketplaces"},
                "orders": {"href": f"{base}/orders"},
            }})
        elif self.path.startswith("/api/marketplaces/1/markets"):
            self._send(MARKETS)
        else:
            self._send([])

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode() if length else "{}"
        if self.path.endswith("/tokens"):
            self._send({
                "token": TOKEN,
                "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                "account": {"id": 1, "name": "dev"},
            })
            return
        submitted.append(json.loads(raw))
        self._send({"id": 42, "marketplaceId": 1, "marketId": 11})


@pytest.fixture
def client():
    submitted.clear()
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}/api"
    fm = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "submit-market-test")
    yield fm
    fm.close()
    httpd.shutdown()


def test_a_buy_bids_the_highest_legal_price(client):
    client.submit_market(1, 11, "BUY", 5)

    assert submitted[0]["price"] == 185
    assert submitted[0]["type"] == "LIMIT"


def test_a_sell_offers_the_lowest_legal_price(client):
    client.submit_market(1, 11, "SELL", 5)

    assert submitted[0]["price"] == 110


def test_whatever_does_not_fill_is_cancelled(client):
    client.submit_market(1, 11, "BUY", 5)

    assert len(submitted) == 2, "submit then cancel"
    assert submitted[1]["type"] == "CANCEL"
    assert submitted[1]["original"] == 42


def test_an_unknown_market_says_so_rather_than_guessing_a_price(client):
    with pytest.raises(InvalidArgumentError):
        client.submit_market(1, 99, "BUY", 5)

    assert submitted == [], "nothing was sent"


# --- the price rule itself, without a server --------------------------------

def _market(minimum: int, maximum: int, tick: int) -> Market:
    return Market(
        id=11, marketplace_id=1, name="Stock", description=None, symbol="STK",
        private_market=False, price_minimum=minimum, price_maximum=maximum,
        price_tick=tick, unit_minimum=1, unit_maximum=100, unit_tick=1,
    )


def test_the_top_of_the_range_is_used_when_it_is_on_a_tick():
    assert _marketable_limit(_market(100, 200, 25), "BUY") == 200


def test_a_range_that_is_not_a_whole_number_of_ticks_rounds_down_to_one():
    """Anchored at price_minimum, not zero: 110/135/160/185, so 199 -> 185.

    Anchoring at zero would give 175, which this market refuses.
    """
    assert _marketable_limit(_market(110, 199, 25), "BUY") == 185


def test_a_fixed_price_market_has_only_its_floor():
    assert _marketable_limit(_market(150, 150, 0), "BUY") == 150
    assert _marketable_limit(_market(150, 150, 0), "SELL") == 150


def test_side_is_read_without_regard_to_case():
    assert _marketable_limit(_market(100, 200, 25), "buy") == 200


def test_a_sideless_market_order_is_refused_rather_than_priced_as_a_sell():
    """A market order that names no side used to price at the bottom of the range -- the most aggressive sell the market accepts -- because the branch was the complement of buy rather than a test for sell. submitMarket takes the side straight from its caller, so a missing argument crossed the wrong side of the book at the worst price rather than failing."""
    with pytest.raises(ValueError, match="must name its side"):
        _marketable_limit(_market(100, 200, 25), None)
