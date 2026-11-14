"""A market order: one limit order, immediate or cancel, capped at the book.

fm-server 4.30 takes a time-in-force on a limit order (fm-server#1035), so a
market order is a LIMIT marked IOC at a cap from the book: the worst price
among others' orders it needs to fill the units. Nothing of it rests, and the
answer says what traded.

Before, it was a limit at the market's extreme and then a cancel: the
remainder rested between the two, and a buy was cash-checked at the extreme,
refusing buys the trader could afford. That is still what it does against a
server or marketplace without IOC -- placed, then cancelled at once.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets, _market_cap
from fm.exceptions import ConnectionFailedError, FlexemarketsError, InvalidArgumentError
from fm.enums import OrderSide, OrderType
from fm.types import Order

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

submitted: list[dict] = []
cancelled: list[str] = []
# Set to a status to refuse the cancel with it.
cancel_refused_with: list[int] = []
# How the server answers a limit: "4.30" with what it did, "old" as before 4.30,
# or "no-ioc" for a marketplace that takes GTC alone.
server: list[str] = []

MARKETS = [{
    "id": 11, "marketplaceId": 1, "symbol": "STK", "name": "Stock",
    "priceMinimum": 110, "priceMaximum": 199, "priceTick": 25,
    "unitMinimum": 1, "unitMaximum": 100, "unitTick": 1,
}]

# Sells at 1.35 (2), 1.60 (2) and 1.85 (5); the reader's own sell at 1.10; a
# buy at 1.10; and a sell in another market.
BOOK = [
    {"id": 1, "type": "LIMIT", "side": "SELL", "units": 2, "price": 135, "marketId": 11, "mine": False},
    {"id": 2, "type": "LIMIT", "side": "SELL", "units": 2, "price": 160, "marketId": 11, "mine": False},
    {"id": 3, "type": "LIMIT", "side": "SELL", "units": 5, "price": 185, "marketId": 11, "mine": False},
    {"id": 4, "type": "LIMIT", "side": "SELL", "units": 9, "price": 110, "marketId": 11, "mine": True},
    {"id": 5, "type": "LIMIT", "side": "BUY", "units": 3, "price": 110, "marketId": 11, "mine": False},
    {"id": 6, "type": "LIMIT", "side": "SELL", "units": 9, "price": 110, "marketId": 12, "mine": False},
]
book: list[list[dict]] = [BOOK]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/v1/marketplaces/1/markets":
            self._send(MARKETS)
        elif self.path == "/api/v1/marketplaces/1/orders?state=ACTIVE":
            self._send(book[0])
        else:
            self._send([])

    def do_DELETE(self):
        cancelled.append(self.path)
        if cancel_refused_with:
            self.send_response(cancel_refused_with[0])
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self._send({"id": 43, "original": 42, "consumer": 42, "type": "CANCEL",
                    "marketplaceId": 1, "marketId": 11})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode() if length else "{}"
        if self.path.startswith("/api/v1/tokens"):
            self._send({
                "token": TOKEN,
                "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                "account": {"id": 1, "name": "dev"},
            })
            return
        assert self.path == "/api/v1/marketplaces/1/orders", self.path
        order = json.loads(raw)
        submitted.append(order)
        answer = {"id": 42, "marketplaceId": 1, "marketId": 11, "type": "LIMIT", "units": order.get("units"),
                  "price": order.get("price")}
        mode = server[0] if server else "4.30"
        if mode == "closed":
            self._send({"error": "SESSION_CLOSED", "message": "The session is closed."}, 400)
            return
        if mode == "no-ioc" and order.get("timeInForce") == "IOC":
            self._send({"error": "ORDER_INVALID", "message": "This marketplace takes GTC orders, not IOC."}, 400)
            return
        if mode == "4.30":
            filled = min(order.get("units", 0), 4)
            answer.update({"timeInForce": order.get("timeInForce"), "filledUnits": filled, "averagePrice": 147.5,
                           "cancelledUnits": order.get("units", 0) - filled})
        self._send(answer)


@pytest.fixture
def client():
    submitted.clear()
    cancelled.clear()
    cancel_refused_with.clear()
    server.clear()
    book[0] = BOOK
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, args=(0.01,), daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}/api"
    fm = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "submit-market-test")
    yield fm
    fm.close()
    httpd.shutdown()


def test_a_buy_is_one_ioc_limit_capped_at_the_book(client):
    order = client.submit_market(1, 11, "BUY", 3)

    assert submitted == [{**submitted[0], "type": "LIMIT", "price": 160, "timeInForce": "IOC", "units": 3}]
    assert cancelled == [], "nothing rests, so nothing to cancel"
    assert (order.filled_units, order.average_price, order.cancelled_units) == (3, 147.5, 0)


def test_the_cap_is_the_worst_price_needed_and_all_of_the_side_when_short(client):
    client.submit_market(1, 11, "BUY", 2)
    client.submit_market(1, 11, "BUY", 50)

    assert [s["price"] for s in submitted] == [135, 185]


def test_a_sell_caps_at_the_buys(client):
    client.submit_market(1, 11, "SELL", 1)

    assert submitted[0]["price"] == 110 and submitted[0]["side"] == "SELL"


def test_an_empty_side_is_refused_without_sending(client):
    book[0] = [o for o in BOOK if o["side"] == "BUY"]

    with pytest.raises(InvalidArgumentError, match="market 11 has no sells"):
        client.submit_market(1, 11, "BUY", 1)

    assert submitted == [] and cancelled == []


def test_a_server_before_time_in_force_is_cancelled_as_before(client):
    """It ignores timeInForce and rests what did not fill; its answer says
    nothing of what traded, which is how this tells."""
    server.append("old")

    order = client.submit_market(1, 11, "BUY", 5)

    assert len(submitted) == 1
    assert cancelled == ["/api/v1/marketplaces/1/orders/42"], "then a DELETE of its remainder"
    assert order.filled_units is None


def test_a_marketplace_without_ioc_is_sent_a_limit_and_a_cancel(client):
    server.append("no-ioc")

    client.submit_market(1, 11, "BUY", 5)

    assert [s.get("timeInForce") for s in submitted] == ["IOC", None]
    assert cancelled == ["/api/v1/marketplaces/1/orders/42"]


def test_any_other_refusal_is_raised_and_not_retried_as_a_plain_limit(client):
    server.append("closed")

    with pytest.raises(InvalidArgumentError, match="session is closed"):
        client.submit_market(1, 11, "BUY", 5)

    assert len(submitted) == 1 and cancelled == []


def test_a_remainder_that_cannot_be_cancelled_says_the_order_is_placed(client):
    """Reporting only "cancel failed" would invite a retry of the whole order,
    and a second trade."""
    server.append("old")
    cancel_refused_with.append(503)

    with pytest.raises(FlexemarketsError, match="Order 42 was placed but its remainder could not be "
                                                "cancelled; it may still be resting. Do not resubmit") as e:
        client.submit_market(1, 11, "BUY", 5)

    assert isinstance(e.value.__cause__, ConnectionFailedError)
    assert len(submitted) == 1


def test_an_unknown_market_says_so_rather_than_guessing_a_price(client):
    with pytest.raises(InvalidArgumentError):
        client.submit_market(1, 99, "BUY", 5)

    assert submitted == [], "nothing was sent"
    assert cancelled == [], "nothing was cancelled"


def test_a_limit_sends_its_time_in_force_only_when_given(client):
    client.submit_limit(1, 11, "BUY", 5, 135)
    client.submit_limit(1, 11, "BUY", 5, 135, time_in_force="fok")

    assert "timeInForce" not in submitted[0]
    assert submitted[1]["timeInForce"] == "FOK"


# --- the cap itself, without a server ---------------------------------------

def _o(side: str, units: int, price: int, market_id: int = 11, mine: bool | None = False) -> Order:
    return Order(id=1, type=OrderType.LIMIT, side=OrderSide.of(side), units=units, price=price,
                 market_id=market_id, mine=mine)


def test_own_orders_and_other_markets_are_left_out():
    book_ = [_o("SELL", 5, 100, mine=True), _o("SELL", 5, 100, market_id=12), _o("SELL", 1, 150)]
    assert _market_cap(book_, 11, "BUY", 1) == 150


def test_an_order_whose_owner_the_server_did_not_say_counts():
    assert _market_cap([_o("SELL", 1, 150, mine=None)], 11, "BUY", 1) == 150


def test_side_is_read_without_regard_to_case():
    assert _market_cap([_o("SELL", 1, 150)], 11, "buy", 1) == 150


def test_a_sideless_market_order_is_refused_rather_than_priced_as_a_sell():
    """A market order that names no side used to price as the most aggressive sell the market accepts -- because the branch was the complement of buy rather than a test for sell. submitMarket takes the side straight from its caller, so a missing argument crossed the wrong side of the book rather than failing."""
    with pytest.raises(ValueError, match="must name its side"):
        _market_cap([_o("SELL", 1, 150)], 11, None, 1)


def test_an_ordinary_limit_order_names_no_counterparty(client):
    """A target of null is a private order aimed at nobody, which the server refuses."""
    client.submit_limit(1, 11, "BUY", 5, 135)

    assert "ownerTarget" not in submitted[0] and "ownerTargetId" not in submitted[0]
    assert submitted[0]["clientDescription"] == "submit-market-test"


def test_a_private_order_names_its_counterparty_by_name_or_by_id(client):
    client.submit_limit(1, 11, "SELL", 5, 135, owner_target="T2")
    client.submit_limit(1, 11, "SELL", 5, 135, owner_target_id=8)

    assert submitted[0]["ownerTarget"] == "T2" and "ownerTargetId" not in submitted[0]
    assert submitted[1]["ownerTargetId"] == 8 and "ownerTarget" not in submitted[1]


def test_naming_the_counterparty_both_ways_is_refused_before_sending(client):
    with pytest.raises(InvalidArgumentError):
        client.submit_limit(1, 11, "SELL", 5, 135, owner_target="T2", owner_target_id=8)

    assert submitted == []


def test_an_order_carries_its_own_reference(client):
    client.submit_limit(1, 11, "BUY", 5, 135, client_description="r1")

    assert submitted[0]["clientDescription"] == "r1"
