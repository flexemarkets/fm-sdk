"""Independent event subscriptions, which only Java could open.

``listen`` is one per connection: a second call replaces the first. The
mechanism for more than one stream was already here -- ``_connect_events``,
private, added so two ``desk()`` calls would not trample each other -- but a
caller who wanted a second stream of their own had no way to ask.

Java has exposed it as ``subscribe`` since Desk sharing landed.
"""

from __future__ import annotations

import json
import queue
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"


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
            self._send({"_links": {}})
        else:
            self._send([])

    def do_POST(self):
        self._send({
            "token": TOKEN,
            "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
            "account": {"id": 1, "name": "dev"},
        })


@pytest.fixture
def client():
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}/api"
    fm = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "subscribe-test")
    yield fm
    fm.close()
    httpd.shutdown()


def test_subscriptions_coexist_rather_than_replacing_each_other(client, monkeypatch):
    """Two subscriptions are two listeners; two listens are one."""
    opened = []

    class FakeListener:
        def __init__(self):
            self.closed = False
            opened.append(self)

        def close(self):
            self.closed = True

    monkeypatch.setattr(
        Flexemarkets, "_connect_events", lambda self, mpid, q: FakeListener()
    )

    first = client.subscribe(1, queue.Queue())
    second = client.subscribe(1, queue.Queue())

    assert len(opened) == 2, "each subscribe opens its own stream"
    assert first is not second

    first()
    assert opened[0].closed is True
    assert opened[1].closed is False, "closing one leaves the other running"


def test_listen_remains_one_per_connection(client, monkeypatch):
    opened = []

    class FakeListener:
        def __init__(self):
            opened.append(self)

        def close(self):
            pass

    monkeypatch.setattr(
        Flexemarkets, "_connect_events", lambda self, mpid, q: FakeListener()
    )

    client.listen(1, queue.Queue())
    client.listen(1, queue.Queue())

    assert client._event_listener is opened[-1], "the second listen replaces the first"
