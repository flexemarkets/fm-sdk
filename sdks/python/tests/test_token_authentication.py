"""Connecting with a token rather than a password.

This never worked. The SDK POSTed /tokens with the bearer header *and* a body
of ``{"username": "|", "password": ""}``, and fm-server answers that with
400 MESSAGE_NOT_READABLE -- verified against a running server, where the same
POST with a real password returns 200. So every token connection failed, in
both this SDK and the TypeScript one, from the day each was written.

A caller holding a token has no account, email or password to present. The
route that exists for them is ``GET /tokens/refresh``, which validates the token
and returns the account and person behind it. The Java SDK has always used it,
and says in a comment that fm-lib-net carried the same branch and an earlier
rewrite dropped it -- this is the third occurrence.

Asserted against a loopback server rather than a stubbed transport, because a
stub is precisely what hid it: a mock answers whatever the test tells it to, and
the server's 400 never appears.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

requests: list[str] = []


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
        requests.append(f"GET {self.path}")
        if self.path == "/api/tokens/refresh":
            self._send({
                "token": TOKEN,
                "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                "account": {"id": 1, "name": "dev"},
            })
        elif self.path == "/api":
            self._send({"_links": {}})
        else:
            self._send([])

    def do_POST(self):
        requests.append(f"POST {self.path}")
        # What fm-server actually answers a token POST with blanks.
        self._send({"error": "MESSAGE_NOT_READABLE", "path": self.path}, status=400)


@pytest.fixture
def server():
    requests.clear()
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield httpd
    httpd.shutdown()


def test_a_token_connects_through_the_refresh_route(server):
    base = f"http://127.0.0.1:{server.server_address[1]}/api"

    client = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "token-test")
    try:
        assert client.user.id == 7
        assert client.account.name == "dev"
    finally:
        client.close()

    assert "GET /api/tokens/refresh" in requests


def test_a_real_length_token_is_not_probed_as_a_filename(server):
    """The second defect, and the reason the first hid behind a short token.

    A real JWT is around 470 characters. Path.is_file() propagates
    OSError: File name too long rather than answering False, so connect(token)
    raised before it reached the network -- while the 68-character fixture
    above fits a filename and sails through. Java's Files.isRegularFile and
    Node's existsSync both answer False for the same input.
    """
    realistic = "eyJhbGciOiJIUzUxMiJ9." + ("A" * 400) + ".signature"
    assert len(realistic) > 255, "shorter than a filename limit proves nothing"

    base = f"http://127.0.0.1:{server.server_address[1]}/api"
    client = Flexemarkets.connect(realistic, f"{base}/marketplaces/1", "token-test")
    try:
        assert client.user.id == 7
    finally:
        client.close()


def test_a_token_never_posts_to_tokens(server):
    """The POST is the defect. A server that refuses it must not be reached."""
    base = f"http://127.0.0.1:{server.server_address[1]}/api"

    client = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "token-test")
    client.close()

    assert not any(r.startswith("POST /api/tokens") for r in requests), requests
