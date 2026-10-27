"""A client that signed in with a password renews its token when it expires.

Tokens live 24 hours. A robot that ran longer ended the first time it asked
for anything after that, or looped on the refusal -- a Utah poller, about 250
times a day at 00Z. A client holding credentials now signs in again once and
repeats the request; one given a bare token cannot, and answers the refusal.

Against a loopback server, as test_token_authentication is: the server says
which token it accepts, and the expired one is refused the way fm-server
refuses it -- 401 with ``ACCOUNT_TOKEN_EXPIRED``.
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
        self.sign_ins = 0
        self.expired_reason = "ACCOUNT_TOKEN_EXPIRED"
        self.plain_401 = False
        self.sign_in_fails_after = None
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
            self.rfile.read(length)
            if self.path in ("/api/v1/tokens", "/api/v1/tokens/refresh"):
                state.sign_ins += 1
                if state.sign_in_fails_after is not None and state.sign_ins > state.sign_in_fails_after:
                    self._send({"error": "SERVICE_UNAVAILABLE"}, status=503)
                    return
                # The first sign-in hands out the token that will expire.
                token = OLD if state.sign_ins == 1 else NEW
                self._send({"token": token, "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                            "account": {"id": 1, "name": "dev"}})
                return
            self._send({"error": "NOT_FOUND"}, status=404)

        def do_GET(self):
            state.requests.append(self.headers.get("Authorization", ""))
            if self.headers.get("Authorization") != f"Bearer {NEW}":
                if state.plain_401:
                    body = b"Unauthorized"
                    self.send_response(401)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                self._send({"error": state.expired_reason, "message": "JWT expired for dev@dev"}, status=401)
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


def _credential(tmp_path) -> str:
    path = tmp_path / "credential"
    path.write_text("account=dev\nemail=dev@dev\npassword=secret\n")
    return str(path)


def test_an_expired_token_is_renewed_once_and_the_request_repeated(server, tmp_path):
    client = Flexemarkets.connect(_credential(tmp_path), f"{server.base}/marketplaces/1", "renewal-test")
    try:
        markets = client.markets(1)
        assert client.token().token == NEW, "the token the client reports is the one it presents"
    finally:
        client.close()

    assert [m.symbol for m in markets] == ["A"]
    assert server.sign_ins == 2
    assert server.requests == [f"Bearer {OLD}", f"Bearer {NEW}"]


def test_a_client_given_a_token_cannot_renew_it_and_says_so(server):
    client = Flexemarkets.connect(OLD, f"{server.base}/marketplaces/1", "renewal-test")
    try:
        with pytest.raises(AuthenticationError):
            client.markets(1)
    finally:
        client.close()

    assert server.sign_ins == 1   # the refresh that validated the token at connect, and no more


def test_a_token_refused_for_another_reason_is_not_renewed(server, tmp_path):
    server.expired_reason = "ACCOUNT_INVALID_CREDENTIALS"
    client = Flexemarkets.connect(_credential(tmp_path), f"{server.base}/marketplaces/1", "renewal-test")
    try:
        with pytest.raises(AuthenticationError):
            client.markets(1)
    finally:
        client.close()

    assert server.sign_ins == 1


def test_a_401_that_is_not_json_is_not_taken_for_an_expiry(server, tmp_path):
    server.plain_401 = True
    client = Flexemarkets.connect(_credential(tmp_path), f"{server.base}/marketplaces/1", "renewal-test")
    try:
        with pytest.raises(AuthenticationError):
            client.markets(1)
    finally:
        client.close()

    assert server.sign_ins == 1


def test_a_renewal_that_fails_answers_with_the_refusal_it_had(server, tmp_path):
    server.sign_in_fails_after = 1
    client = Flexemarkets.connect(_credential(tmp_path), f"{server.base}/marketplaces/1", "renewal-test")
    try:
        with pytest.raises(AuthenticationError, match="JWT expired"):
            client.markets(1)
    finally:
        client.close()

    assert server.sign_ins == 2


def test_a_token_another_thread_already_renewed_is_not_renewed_again(server, tmp_path):
    client = Flexemarkets.connect(_credential(tmp_path), f"{server.base}/marketplaces/1", "renewal-test")
    try:
        assert client._renew_token("Bearer something-older") is True
    finally:
        client.close()

    assert server.sign_ins == 1
