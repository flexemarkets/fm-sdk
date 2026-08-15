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
                "usersJson": {"href": f"{base}/usersJson"},
            }})
        else:
            self._send([])

    def do_POST(self):
        self._record()
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
