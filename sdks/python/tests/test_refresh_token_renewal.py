"""A client given a refresh token signs in with it, and renews with each one
it is handed after.

A robot fm-server launches used to run on the participant's access token, and
stopped when that expired: it held no password to sign in again with. Once
access tokens live two hours rather than a day, that is every robot left
running through a class. Now it is handed a refresh token of its own. Against a
loopback server that, as fm-server does, takes each refresh token once,
answers with the next, and refuses one presented again.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets
from fm.exceptions import AuthenticationError

OLD = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvbGQifQ.b2xk"
NEW = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJuZXcifQ.bmV3"


class Server:
    def __init__(self) -> None:
        # Each live refresh token, and the access token and next refresh token it is traded for.
        self.live = {"rt-1": (OLD, "rt-2"), "rt-2": (NEW, "rt-3")}
        self.renewals: list[str] = []
        self.requests: list[str] = []


def _handler(state: Server):
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

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
            if self.path == "/api/tokens/renew":
                presented = body.get("refreshToken", "")
                state.renewals.append(presented)
                traded = state.live.pop(presented, None)
                if traded is None:
                    self._send({"error": "REFRESH_TOKEN_INVALID", "message": "The refresh token is not a live one"},
                               status=401)
                    return
                self._send({"token": traded[0], "refreshToken": traded[1],
                            "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                            "account": {"id": 1, "name": "dev"}})
                return
            self._send({"error": "NOT_FOUND"}, status=404)

        def do_GET(self):
            state.requests.append(self.headers.get("Authorization", ""))
            if self.headers.get("Authorization") != f"Bearer {NEW}":
                self._send({"error": "ACCOUNT_TOKEN_EXPIRED", "message": "JWT expired for dev@dev"}, status=401)
                return
            self._send([{"id": 11, "marketplaceId": 1, "symbol": "A", "name": "A"}])

    return Handler


@pytest.fixture
def server():
    state = Server()
    httpd = HTTPServer(("127.0.0.1", 0), _handler(state))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    state.base = f"http://127.0.0.1:{httpd.server_address[1]}/api"
    yield state
    httpd.shutdown()


def test_it_signs_in_with_the_refresh_token_and_renews_with_the_next_when_the_access_token_expires(server):
    client = Flexemarkets.connect("refresh:rt-1", f"{server.base}/marketplaces/1", "refresh-test")
    try:
        assert client.token().token == OLD, "the access token the first renewal handed out"
        markets = client.markets(1)
        assert client.token().token == NEW
    finally:
        client.close()

    assert [m.symbol for m in markets] == ["A"]
    # Each refresh token presented once, the second the one the first renewal handed back.
    assert server.renewals == ["rt-1", "rt-2"]
    assert server.requests == [f"Bearer {OLD}", f"Bearer {NEW}"]


def test_a_credential_file_carries_the_refresh_token_too(server, tmp_path):
    path = tmp_path / "credential"
    path.write_text("refresh-token=rt-2\n")
    client = Flexemarkets.connect(str(path), f"{server.base}/marketplaces/1", "refresh-test")
    try:
        client.markets(1)
    finally:
        client.close()

    assert server.renewals == ["rt-2"]


def test_a_refresh_token_the_server_will_not_take_is_refused_at_connect(server):
    with pytest.raises(AuthenticationError):
        Flexemarkets.connect("refresh:rt-unknown", f"{server.base}/marketplaces/1", "refresh-test")


def test_an_expired_token_whose_renewal_is_refused_answers_with_the_refusal_it_had(server):
    del server.live["rt-2"]
    client = Flexemarkets.connect("refresh:rt-1", f"{server.base}/marketplaces/1", "refresh-test")
    try:
        with pytest.raises(AuthenticationError, match="JWT expired"):
            client.markets(1)
    finally:
        client.close()

    assert server.renewals == ["rt-1", "rt-2"]


def test_a_token_another_thread_already_renewed_is_not_renewed_again(server):
    # A second renewal of the same token would present a used refresh token, and end the family.
    client = Flexemarkets.connect("refresh:rt-1", f"{server.base}/marketplaces/1", "refresh-test")
    try:
        assert client._renew_token("Bearer something-older")
    finally:
        client.close()

    assert server.renewals == ["rt-1"]
