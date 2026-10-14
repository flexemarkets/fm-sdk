"""The Python client reports its own round trips on Client-Timing.

Robot classes' latency was invisible: only fm-ui and the Java SDK sent
``Client-Timing``, so fm-server's Connections view had nothing for a Python
robot. Each request now carries what the previous one cost -- ``rtt=<ns>``, and
``;net=<ns>`` when the server said what its own share was -- in the format the
Java SDK and fm-ui use, and fm-server's ApiMetricsFilter reads.

Against a loopback server, so the header asserted is the one actually sent.
"""

from __future__ import annotations

import json
import re
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from fm.client import Flexemarkets

timings: list[str | None] = []
server_timing: dict[str, str | None] = {"value": "st=1000"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, payload):
        timings.append(self.headers.get("Client-Timing"))
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if server_timing["value"] is not None:
            self.send_header("Server-Timing", server_timing["value"])
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        self._send({
            "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl",
            "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
            "account": {"id": 1, "name": "dev"},
        })

    def do_GET(self):
        self._send({})


@pytest.fixture
def client(tmp_path):
    timings.clear()
    server_timing["value"] = "st=1000"
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}/api"
    credential = tmp_path / "credential"
    credential.write_text("account=dev\nemail=dev@dev\npassword=pw\n")
    c = Flexemarkets(credential=str(credential), endpoint=f"{base}/marketplaces/1")
    yield c
    c.close()
    httpd.shutdown()


def _field(header: str, name: str) -> int | None:
    m = re.search(rf"(?:^|;){name}=(\d+)", header)
    return int(m.group(1)) if m else None


def test_the_first_request_carries_no_timing(client):
    assert timings[0] is None, "sign-in has nothing to report yet"


def test_a_later_request_reports_what_the_one_before_cost(client):
    client._http.get("/version")
    header = timings[-1]
    assert header is not None
    assert _field(header, "rtt") > 0


def test_the_servers_share_is_taken_out_of_the_wire(client):
    client._http.get("/version")
    header = timings[-1]
    rtt, net = _field(header, "rtt"), _field(header, "net")
    assert net is not None
    assert net == max(0, rtt - 1000)


def test_without_the_servers_share_the_wire_is_not_claimed(client):
    server_timing["value"] = None
    client._http.get("/version")          # answered without Server-Timing
    client._http.get("/version")          # reports that one
    header = timings[-1]
    assert _field(header, "rtt") > 0
    assert "net=" not in header


def test_each_figure_is_reported_once(client):
    client._http.get("/version")
    client._http.get("/version")
    reported = [t for t in timings if t is not None]
    assert len(reported) == len(timings) - 1, "every request after the first carries exactly one figure"
