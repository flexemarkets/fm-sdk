"""The management surface: staging opening positions and reading them back.

Mirrors the Java SDK's ManagementApiTest. Asserted against a real loopback
server rather than a mocked transport, because what matters is the request that
actually goes out -- above all the field names in its body -- and a mock would
assert only that the client called itself the way this test expected.
"""

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets
from fm.exceptions import InvalidArgumentError
from fm.types import Holding, Security

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

# One allotment, spelling the positions the way the server does: "grants".
ALLOTMENTS = [
    {
        "id": 5,
        "allocationId": 42,
        "marketplaceId": 1,
        "ownerId": 8,
        "name": "alice",
        "assets": {"cash": 10000, "grants": [{"marketId": 10, "units": 50}]},
    }
]

requests: list[tuple[str, str]] = []
bodies: dict[str, str] = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep the test output clean
        pass

    def _send(self, payload, content_type="application/json"):
        body = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _record(self):
        requests.append((self.command, self.path))
        length = int(self.headers.get("Content-Length") or 0)
        bodies[f"{self.command} {self.path}"] = (
            self.rfile.read(length).decode("utf-8", "replace") if length else ""
        )

    def do_GET(self):
        self._record()
        if self.path.startswith("/api/tokens"):
            self._send({
                "token": TOKEN,
                "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                "account": {"id": 1, "name": "dev"},
            })
        elif self.path.startswith("/api/v1/marketplaces/1/allotments"):
            self._send(ALLOTMENTS)
        elif self.path == "/api":
            base = f"http://127.0.0.1:{self.server.server_address[1]}/api"
            self._send({"_links": {
                "marketplaces": {"href": f"{base}/marketplaces"},
                "symbolTradesJson": {"href": f"{base}/symbolTradesJson"},
                "usersJson": {"href": f"{base}/usersJson"},
            }})
        elif self.path.startswith("/api/marketplaces/1/holdings/downloads"):
            self._send("owner,cash\nalice,10000\n", "text/csv")
        elif self.path.startswith("/api/marketplaces/1/sessions"):
            self._send([{"id": 300, "state": "CLOSED"}])
        elif self.path.startswith("/api/marketplaces/1/connections"):
            self._send([{"id": 9, "ownerId": 8, "marketplaceId": 1, "sessionId": 300}])
        elif self.path.startswith("/api/symbolTradesJson"):
            # The symbol-keyed route answers with the trade id in "original"
            # and no symbol on the order.
            self._send([{"id": 0, "original": 4242, "units": 5, "price": 950}])
        else:
            self._send([])

    def do_POST(self):
        self._record()
        if self.path == "/api/v1/marketplaces":
            self._send({"id": 77, "name": "simple-dividend", "markets": []})
            return
        # Sign-in posts here too; answering it with an allotment list makes the
        # connection fail somewhere far from the cause.
        if self.path.startswith("/api/tokens"):
            self._send({
                "token": TOKEN,
                "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                "account": {"id": 1, "name": "dev"},
            })
            return
        self._send(ALLOTMENTS)


@pytest.fixture
def server():
    requests.clear()
    bodies.clear()
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield httpd
    httpd.shutdown()


@pytest.fixture
def fm(server):
    base = f"http://127.0.0.1:{server.server_address[1]}/api"
    client = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "management-test")
    yield client
    client.close()


def test_allotments_are_read_from_the_v1_route(fm):
    allotments = fm.allotments(1, 42)

    assert len(allotments) == 1
    assert allotments[0].assets.securities[0].units == 50
    assert ("GET", "/api/v1/marketplaces/1/allotments?allocation=42") in requests


def test_allocate_sends_positions_as_grants(fm):
    """The failure this guards is silent.

    The server reads opening positions from ``grants``. Send ``securities``
    instead and it finds none, creates the allocation with cash and no
    positions, and answers 200 -- everything downstream then runs an experiment
    whose participants hold nothing.
    """
    holding = Holding(
        marketplace_id=1, owner_id=8, name="alice", cash=10000, available_cash=10000,
        securities=[Security(market_id=10, units=50, available_units=50)],
    )

    fm.allocate(1, [holding])

    body = bodies["POST /api/marketplaces/1/allocations"]
    assert '"grants"' in body, "the server reads opening positions from 'grants'"
    assert '"securities"' not in body
    assert '"cash": 10000' in body or '"cash":10000' in body


def test_allocate_returns_what_the_server_created(fm):
    holding = Holding(marketplace_id=1, owner_id=8, name="alice", cash=10000)

    created = fm.allocate(1, [holding])

    assert len(created) == 1
    back = created[0]
    assert back.owner_id == 8
    assert back.allocation_id == 42
    assert back.cash == 10000
    # An opening position has committed nothing, and predates the session it
    # will be opened under.
    assert back.available_cash == 10000
    assert back.session_id == 0
    assert back.securities[0].market_id == 10


def test_a_marketplace_is_created_from_its_json_definition(fm):
    created = fm.create_marketplace_from_json(
        '{"name":"simple-dividend","markets":[{"symbol":"STK"}]}')

    assert created.id == 77
    assert ("POST", "/api/v1/marketplaces") in requests
    assert '"STK"' in bodies["POST /api/v1/marketplaces"], "the definition is forwarded, not rebuilt"


def test_malformed_marketplace_json_fails_before_any_request(fm):
    """Parsed before it is sent, so a bad definition fails here rather than as
    a 400 whose message is about a document the caller cannot see."""
    with pytest.raises(InvalidArgumentError, match="not valid JSON"):
        fm.create_marketplace_from_json("{not json")

    assert not any(path == "/api/v1/marketplaces" for _, path in requests)


def test_a_short_allowance_is_read_under_either_name():
    """fm-server's Asset emits initialShortUnits for a live session; the
    allotments path emits shortUnits. Before this field existed both were
    dropped in silence, so a participant permitted to short 50 read as one
    permitted to short nothing."""
    from fm.client import _parse_security

    assert _parse_security({"marketId": 10, "shortUnits": 50}).short_units == 50
    assert _parse_security({"marketId": 10, "initialShortUnits": 50}).short_units == 50
    assert _parse_security({"marketId": 10}).short_units == 0, "absent means none, not None"


def test_allocate_sends_the_short_allowance(fm):
    holding = Holding(
        marketplace_id=1, owner_id=8, name="alice", cash=10000, available_cash=10000,
        securities=[Security(market_id=10, units=5, available_units=55, short_units=50)],
    )

    fm.allocate(1, [holding])

    body = bodies["POST /api/marketplaces/1/allocations"]
    assert '"shortUnits": 50' in body or '"shortUnits":50' in body


def test_sessions_and_connections_filter_on_session_ids(fm):
    """The filter is spelled ``sessionIds`` here and ``sessions`` on the
    holdings download. Getting it wrong is not an error -- it is an unfiltered
    answer that looks right until someone checks the totals.
    """
    fm.sessions(1, [300, 301])
    fm.connections(1, [300])

    paths = [p for _, p in requests]
    assert any("/sessions?sessionIds=300,301" in p for p in paths)
    assert any("/connections?sessionIds=300" in p for p in paths)


def test_the_holdings_download_filters_on_sessions(fm):
    fm.download_holdings(1, [300])

    assert any("/holdings/downloads?sessions=300" in p for _, p in requests)


def test_a_connection_carries_its_session(fm):
    """A connection belongs to a session, and that is how a study works out who
    was present in a run. The field was absent until 0.0.11, so every connection
    read as belonging to none.
    """
    connections = fm.connections(1, [300])

    assert len(connections) == 1
    assert connections[0].session_id == 300


def test_trades_carry_their_id_and_symbol(fm):
    """Trades come back with the trade id in ``original`` and no symbol, because
    the query already fixed it. Both are filled in, so the result is a trade
    list rather than half-populated orders.
    """
    trades = fm.trades(1, "STK")

    assert len(trades) == 1
    assert trades[0].id == 4242, "the trade id, taken from original"
    assert trades[0].symbol == "STK"
    assert any("symbol=STK" in p for _, p in requests)


def test_empty_filters_fall_back_to_the_unfiltered_routes(fm):
    """An empty filter means "now", and asks for no filter at all."""
    fm.sessions(1, [])
    fm.connections(1, [])
    fm.download_holdings(1, [])

    paths = [p for _, p in requests]
    assert not any("sessionIds=" in p for p in paths)
    assert not any("?sessions=" in p for p in paths)
