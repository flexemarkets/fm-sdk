"""The management surface: staging opening positions and reading them back.

Mirrors the Java SDK's ManagementApiTest. Asserted against a real loopback
server rather than a mocked transport, because what matters is the request that
actually goes out -- above all the field names in its body -- and a mock would
assert only that the client called itself the way this test expected.
"""

import json
from datetime import UTC, datetime
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets
from fm.exceptions import HttpError, InvalidArgumentError
from fm.types import Holding, Security, WidgetPush, WidgetTarget

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

# One stored widget per scope, as fm-server answers a push or a read of all.
WIDGETS = [
    {
        "createdDate": "2026-10-01T09:00:00", "lastModifiedDate": "2026-10-01T09:00:05",
        "id": 31, "marketplaceId": 1, "scope": "MARKETPLACE", "key": "score", "title": "Score",
        "content": {"kind": "text", "lines": ["round 1"]},
    },
    {
        "id": 32, "marketplaceId": 1, "scope": "USER", "userId": 8, "key": "values",
        "emphasis": "strong", "ttlSeconds": 60,
        "content": {"kind": "kv", "items": [{"label": "value", "value": 120, "format": "price"}]},
    },
]

requests: list[tuple[str, str]] = []
bodies: dict[str, str] = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep the test output clean
        pass

    def _send(self, payload, content_type="application/json", status=200):
        body = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
        self.send_response(status)
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
        elif self.path == "/api/v1/marketplaces/1/widgets?participant=all":
            self._send(WIDGETS)
        elif self.path == "/api/v1/marketplaces/1/allocations/42/allotments":
            self._send(ALLOTMENTS)
        elif (self.path.startswith("/api/v1/marketplaces/1/holdings")
              and "text/csv" in (self.headers.get("Accept") or "")):
            self._send("owner,cash\nalice,10000\n", "text/csv")
        elif self.path.startswith("/api/v1/marketplaces/1/sessions"):
            self._send([{"id": 300, "state": "CLOSED"}])
        elif self.path.startswith("/api/v1/marketplaces/1/connections"):
            self._send([{"id": 9, "ownerId": 8, "marketplaceId": 1, "sessionId": 300}])
        elif self.path.startswith("/api/v1/marketplaces/1/orders?state=TRADED"):
            # The traded legs carry the trade id in "original" and no symbol
            # on the order.
            self._send([{"id": 0, "original": 4242, "units": 5, "price": 950}])
        else:
            self._send([])

    def do_DELETE(self):
        self._record()
        if self.path.startswith("/api/v1/marketplaces/2/"):
            # A marketplace that is not there: the 404 carries a failure document.
            self._send({"error": "MARKETPLACE_NOT_FOUND", "message": "no marketplace 2",
                        "status": "NOT_FOUND"}, status=404)
            return
        # 204 for the key that exists, an empty 404 for one that does not.
        found = self.path.split("?")[0].endswith("/score")
        self.send_response(204 if found else 404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        self._record()
        if self.path == "/api/v1/marketplaces/1/widgets":
            self._send(WIDGETS)
            return
        if self.path == "/api/v1/marketplaces":
            self._send({"id": 77, "name": "simple-dividend", "markets": []})
            return
        if self.path == "/api/otp/manager":
            self._send({
                "expiresAt": "2026-08-15T18:00:00Z",
                "otps": [{"userId": 1, "email": "alice@lab.edu", "otp": "123456"}],
            })
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
    assert ("GET", "/api/v1/marketplaces/1/allocations/42/allotments") in requests


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

    body = bodies["POST /api/v1/marketplaces/1/allocations"]
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

    body = bodies["POST /api/v1/marketplaces/1/allocations"]
    assert '"shortUnits": 50' in body or '"shortUnits":50' in body


def test_sessions_and_connections_are_never_filtered_on_the_wire(fm):
    """The SDK no longer pretends these filter.

    This asserted the opposite: that sessions(1, ids) put ?sessionIds= on the
    wire. It did -- and the server ignored it. GET /marketplaces/{id}/sessions
    and /connections accept only ``format``, so the answer was the whole
    history looking like a filtered one. Asserting the request without
    asserting the response is how a defect becomes a requirement.
    """
    fm.sessions(1)
    fm.connections(1)

    assert not any("sessionIds=" in r for _, r in requests)
    # Both are V1, which needs no format= to avoid HAL: no query at all.
    assert ("GET", "/api/v1/marketplaces/1/sessions") in requests
    assert ("GET", "/api/v1/marketplaces/1/connections") in requests


def test_the_holdings_download_filters_on_sessions(fm):
    fm.download_holdings(1, [300])

    assert ("GET", "/api/v1/marketplaces/1/holdings?sessions=300") in requests


def test_a_connection_carries_its_session(fm):
    """A connection belongs to a session, and that is how a study works out who
    was present in a run. The field was absent until 0.0.11, so every connection
    read as belonging to none.
    """
    connections = fm.connections(1)

    assert len(connections) == 1
    assert connections[0].session_id == 300


def test_trades_carry_their_id_and_symbol(fm):
    """Traded legs come back with the trade id in ``original`` and no symbol,
    because the query already fixed it. Both are filled in, so the result is a trade
    list rather than half-populated orders.
    """
    trades = fm.trades(1, "STK")

    assert len(trades) == 1
    assert trades[0].id == 4242, "the trade id, taken from original"
    assert trades[0].symbol == "STK"
    assert ("GET", "/api/v1/marketplaces/1/orders?state=TRADED&symbol=STK&limit=5000") in requests


def test_an_empty_filter_falls_back_to_the_unfiltered_route(fm):
    """An empty filter means "now", and asks for no filter at all."""
    assert fm.download_holdings(1, []) == "owner,cash\nalice,10000\n"

    assert ("GET", "/api/v1/marketplaces/1/holdings") in requests
    assert not any("?sessions=" in p for _, p in requests)


def test_manager_otp_bundles_are_minted_for_the_users_asked(fm):
    """These are credentials, so the shape matters: a bundle read as empty
    would send a class away with no way to sign in, and look like success.
    """
    bundle = fm.manager_otp_bundle([1, 2])

    assert bundle.expires_at == datetime(2026, 8, 15, 18, 0, tzinfo=UTC)
    assert len(bundle.otps) == 1
    assert bundle.otps[0].user_id == 1
    assert bundle.otps[0].otp == "123456"
    assert '"userIds":[1,2]' in bodies["POST /api/otp/manager"].replace(" ", "")


def test_the_token_is_the_one_signed_in_with(fm):
    """Handed back so a caller can open a sibling connection on the same
    identity without holding the password again.
    """
    assert fm.token().token == TOKEN


def test_push_widgets_posts_one_array(fm):
    """A list goes up as one JSON array, whatever its length -- the server's
    rate limit counts requests, and sixty single pushes at session open is what
    it is there to refuse. The target travels nested, as the server reads it,
    and a marketplace target carries no userId.
    """
    stored = fm.push_widgets(1, [
        WidgetPush(key="score", title="Score", target=WidgetTarget("MARKETPLACE"),
                   content={"kind": "text", "lines": ["round 1"]}),
        WidgetPush(key="values", emphasis="strong", ttl_seconds=60,
                   target=WidgetTarget("USER", user_id=8),
                   content={"kind": "kv", "items": [{"label": "value", "value": 120}]}),
    ])

    assert [r for r in requests if r[0] == "POST" and not r[1].startswith("/api/tokens")] == [
        ("POST", "/api/v1/marketplaces/1/widgets")]
    body = json.loads(bodies["POST /api/v1/marketplaces/1/widgets"])
    assert isinstance(body, list) and len(body) == 2
    assert body[0]["target"] == {"scope": "MARKETPLACE"}
    assert body[1]["target"] == {"scope": "USER", "userId": 8}
    assert body[1]["ttlSeconds"] == 60
    assert "ttl_seconds" not in body[1], "the wire is camelCase"
    assert "title" not in body[1], "an absent title is absent, not null"

    assert [w.id for w in stored] == [31, 32]
    assert stored[0].last_modified_date == datetime(2026, 10, 1, 9, 0, 5, tzinfo=UTC)
    assert stored[1].user_id == 8
    assert stored[1].ttl_seconds == 60


def test_all_widgets_reads_the_manager_route(fm):
    widgets = fm.all_widgets(1)

    assert ("GET", "/api/v1/marketplaces/1/widgets?participant=all") in requests
    assert [w.key for w in widgets] == ["score", "values"]
    assert widgets[1].content["kind"] == "kv"


def test_remove_widget_answers_whether_anything_was_there(fm):
    """204 is removed and an empty 404 is nothing to remove: both are answers,
    and a robot clearing a key it may never have pushed should not have to
    catch an exception to find out which."""
    assert fm.remove_widget(1, "score") is True
    assert fm.remove_widget(1, "absent") is False
    assert fm.remove_widget(1, "score", user_id=8) is True

    assert ("DELETE", "/api/v1/marketplaces/1/widgets/score") in requests
    assert ("DELETE", "/api/v1/marketplaces/1/widgets/absent") in requests
    assert ("DELETE", "/api/v1/marketplaces/1/participants/8/widgets/score") in requests


def test_remove_widget_raises_a_404_that_says_something_else(fm):
    """A 404 carrying a failure document is not "nothing there" -- it is a
    marketplace that does not exist, and reading it as False would let a robot
    pointed at the wrong marketplace believe its clean-up worked."""
    with pytest.raises(HttpError) as raised:
        fm.remove_widget(2, "score")
    assert raised.value.status_code == 404


def test_approving_a_name_no_account_has_says_so_and_approves_nothing(fm):
    """V1 approves by id, so the name is looked up first. A name in no account
    is the caller's mistake, said as one -- not a POST to an id made up."""
    with pytest.raises(InvalidArgumentError, match="No account named 'ghost'"):
        fm.approve_account("ghost")

    assert ("GET", "/api/v1/accounts") in requests
    assert not any(path.endswith("/approvals") for _, path in requests)
