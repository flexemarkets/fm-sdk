"""The User-Agent names the version installed, not 0.0.0 (fm-server#1012).

fm-server keys its endpoint metrics by client and version, cut to
major.minor, to know who still calls a route before it is retired. The
version came from the repository's VERSION file, four levels above
``client.py`` -- which exists in a checkout and nowhere a wheel installs to,
so every pip install reported ``fm-sdk-python/0.0.0``. Production's
rejection log carried exactly that.
"""

from __future__ import annotations

import importlib.metadata
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

from fm import client as fm_client
from fm.client import Flexemarkets, _read_version

REPO_VERSION = (Path(__file__).resolve().parents[3] / "VERSION").read_text().strip()
TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"


def test_a_checkout_reads_the_version_file():
    assert _read_version() == REPO_VERSION


def test_an_installed_wheel_reads_its_own_metadata(tmp_path):
    # Installed, there is no VERSION four levels up; the metadata hatch
    # stamped at build time is what knows the version.
    absent = tmp_path / "VERSION"
    assert _read_version(absent) == importlib.metadata.version("fm-sdk")
    assert _read_version(absent) != "0.0.0"


def test_nothing_to_read_is_0_0_0(tmp_path):
    assert _read_version(tmp_path / "VERSION", "no-such-distribution") == "0.0.0"


agents: list[str] = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        agents.append(self.headers.get("User-Agent", ""))
        if self.path == "/api/tokens/refresh":
            payload = {
                "token": TOKEN,
                "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
                "account": {"id": 1, "name": "dev"},
            }
        else:
            payload = []
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_POST = do_GET


@pytest.fixture
def server():
    agents.clear()
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield httpd
    httpd.shutdown()


def test_the_wire_carries_it(server):
    base = f"http://127.0.0.1:{server.server_address[1]}/api"
    client = Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "user-agent-test")
    try:
        assert agents, "no request reached the server"
        assert set(agents) == {f"fm-sdk-python/{REPO_VERSION}"}
        assert fm_client._FM_NETWORK_CLIENT == f"fm-sdk-python/{REPO_VERSION}"
    finally:
        client.close()
