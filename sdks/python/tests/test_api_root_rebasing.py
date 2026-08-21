"""An API root whose links name somewhere other than the host that was dialled.

The defect, 2026-08-21: production on ``api.adhocmarkets.com`` answers
``GET /api`` with every href spelled ``http://``, while the same application on
``api.flexemarkets.com`` spells them ``https://``. The cause was an edge
reaching the origin over a plaintext leg, so the server was told the request
arrived on HTTP and built its links accordingly. Every call that goes through a
link then leaves on plain HTTP and meets the edge's redirect.

Following the redirect does not repair it: a 301 on a POST is re-sent as a GET
with the body dropped, so an order is never placed and a session never opens.
The links have to be pointed back at the host the token came from.
"""

from __future__ import annotations

import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import ApiRoot, Flexemarkets, _rebase_api_root

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

origin_requests: list[str] = []
decoy_requests: list[str] = []

# Set once the decoy is listening; the origin has to name it in its links.
decoy_base = ""


def _root_naming(origin: str) -> ApiRoot:
    return ApiRoot(links={
        "marketplaces": f"{origin}/api/marketplaces",
        "orders": f"{origin}/api/orders",
    })


def test_links_are_moved_to_the_host_that_was_dialled() -> None:
    rebased = _rebase_api_root(
        _root_naming("http://api.example.com"), "https://api.example.com/api")

    assert rebased.links == {
        "marketplaces": "https://api.example.com/api/marketplaces",
        "orders": "https://api.example.com/api/orders",
    }


def test_a_port_is_part_of_the_origin_and_moves_with_it() -> None:
    rebased = _rebase_api_root(
        _root_naming("http://127.0.0.1:9999"), "http://127.0.0.1:8080/api")

    assert rebased.links["marketplaces"] == "http://127.0.0.1:8080/api/marketplaces"


def test_a_uri_template_survives_the_rewrite() -> None:
    root = ApiRoot(links={
        "marketplaces": "http://api.example.com/api/marketplaces{?page,size,sort*}"})

    rebased = _rebase_api_root(root, "https://api.example.com/api")

    assert rebased.links["marketplaces"] == (
        "https://api.example.com/api/marketplaces{?page,size,sort*}")


def test_a_relative_href_is_left_alone() -> None:
    root = ApiRoot(links={"marketplaces": "/api/marketplaces"})

    rebased = _rebase_api_root(root, "https://api.example.com/api")

    assert rebased.links["marketplaces"] == "/api/marketplaces"


def test_a_rewrite_says_so_and_names_both_origins(caplog) -> None:
    with caplog.at_level(logging.WARNING, logger="fm.client"):
        _rebase_api_root(_root_naming("http://api.example.com"),
                         "https://api.example.com/api")

    assert "http://api.example.com" in caplog.text
    assert "https://api.example.com" in caplog.text
    assert "forwarding the request scheme" in caplog.text


def test_a_root_that_already_agrees_is_silent(caplog) -> None:
    with caplog.at_level(logging.WARNING, logger="fm.client"):
        rebased = _rebase_api_root(_root_naming("https://api.example.com"),
                                   "https://api.example.com/api")

    assert rebased.links == _root_naming("https://api.example.com").links
    assert caplog.text == ""


# ---------------------------------------------------------------------------
# End to end, against a real loopback server: the unit tests above would all
# still pass if the rewrite were never wired into the connection.
# ---------------------------------------------------------------------------

class OriginHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep the test output clean
        pass

    def _send(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        origin_requests.append(f"GET {self.path}")
        if self.path == "/api":
            # Every link names the decoy, which is what a server behind a
            # misconfigured edge does.
            self._send({"_links": {
                "marketplaces": {
                    "href": f"{decoy_base}/api/marketplaces" "{?page,size,sort*}"},
            }})
        elif self.path.startswith("/api/marketplaces/1/markets"):
            self._send([{"id": 11, "symbol": "STK", "name": "Stock"}])
        else:
            self._send([])

    def do_POST(self):
        origin_requests.append(f"POST {self.path}")
        self._send({
            "token": TOKEN,
            "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
            "account": {"id": 1, "name": "dev"},
        })


class DecoyHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        decoy_requests.append(f"GET {self.path}")
        self.send_response(401)
        self.send_header("Content-Length", "0")
        self.end_headers()


@pytest.fixture
def servers():
    global decoy_base
    origin_requests.clear()
    decoy_requests.clear()

    decoy = HTTPServer(("127.0.0.1", 0), DecoyHandler)
    threading.Thread(target=decoy.serve_forever, daemon=True).start()
    decoy_base = f"http://127.0.0.1:{decoy.server_address[1]}"

    origin = HTTPServer(("127.0.0.1", 0), OriginHandler)
    threading.Thread(target=origin.serve_forever, daemon=True).start()

    yield origin
    origin.shutdown()
    decoy.shutdown()


def test_a_read_through_a_link_stays_on_the_host_that_was_dialled(servers):
    base = f"http://127.0.0.1:{servers.server_address[1]}/api"
    client = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "rebase-test")
    try:
        markets = client.markets(1)
    finally:
        client.close()

    assert [m.symbol for m in markets] == ["STK"]
    assert any(r.startswith("GET /api/marketplaces/1/markets") for r in origin_requests)
    assert decoy_requests == []
