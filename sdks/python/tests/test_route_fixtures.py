"""Every route fixture: where each client call goes, against a loopback server.

See ``sdks/fixtures/routes/README.md``. Each case names a call, its arguments
and the requests it must send, in order. The server here answers sign-in
(``/api/tokens*``, any method) and, for the rest, exactly the requests the case
lists -- in that order, with that case's response. Anything else fails the
case, and the request that matters most is ``GET /api``: 0.4 reads no API
root, and this is what keeps it from drifting back.
"""

from __future__ import annotations

import json
import re
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qsl, urlsplit

import pytest

from fm.client import Flexemarkets
from fm.types import Holding, Security, TickGrid

ROUTES = Path(__file__).resolve().parents[2] / "fixtures" / "routes" / "routes.json"
CASES: list[dict[str, Any]] = json.loads(ROUTES.read_text())["cases"]

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"
SIGNED_IN = {
    "token": TOKEN,
    "person": {"id": 7, "accountId": 1, "email": "dev@dev", "roles": ["ROLE_ADMIN"]},
    "account": {"id": 1, "name": "dev"},
}


def _snake(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])([A-Z])", r"_\1", name).lower()


def _csv_file(content: str, tmp: Path) -> str:
    path = tmp / "upload.csv"
    path.write_bytes(content.encode())
    return str(path)


def _holding(marketplace_id: int, h: dict[str, Any]) -> Holding:
    return Holding(
        marketplace_id=marketplace_id, owner_id=h["ownerId"], name=h["name"], cash=h["cash"],
        securities=[Security(market_id=s["marketId"], units=s["units"])
                    for s in h.get("securities", [])],
    )


def _grid(g: dict[str, int]) -> TickGrid:
    return TickGrid(g["minimum"], g["maximum"], g["tick"])


# The calls whose Python signature is not the case's arguments, snake_cased and
# passed by keyword. A call missing from here and from the client fails as a
# missing attribute, which is the message that says to add it.
CALLS: dict[str, Callable[[Flexemarkets, dict[str, Any], Path], Any]] = {
    "orders": lambda fm, a, _: fm.orders(
        a["marketplaceId"], symbol=a.get("symbol"), session_ids=a.get("sessionIds")),
    "createMarketplaceFromJson": lambda fm, a, _: fm.create_marketplace_from_json(a["json"]),
    "createMarket": lambda fm, a, _: fm.create_market(
        a["marketplaceId"], a["symbol"], a["name"], _grid(a["price"]), _grid(a["units"]),
        a["privateMarket"]),
    "createUser": lambda fm, a, _: fm.create_user(
        a["email"], a["password"], a["firstName"], a["lastName"], *a["roles"]),
    "allocate": lambda fm, a, _: fm.allocate(
        a["marketplaceId"], [_holding(a["marketplaceId"], h) for h in a["holdings"]]),
    "uploadHoldings": lambda fm, a, tmp: fm.upload_holdings(
        a["marketplaceId"], _csv_file(a["csv"], tmp)),
    "uploadState": lambda fm, a, tmp: fm.upload_state(
        a["marketplaceId"], _csv_file(a["csv"], tmp)),
}


def _call(fm: Flexemarkets, case: dict[str, Any], tmp: Path) -> Any:
    special = CALLS.get(case["call"])
    if special is not None:
        return special(fm, case["args"], tmp)
    method = getattr(fm, _snake(case["call"]))
    return method(**{_snake(k): v for k, v in case["args"].items()})


def _mismatch(expected: dict[str, Any], method: str, target: str,
              headers: Any, body: bytes) -> str | None:
    """What is wrong with this request against the one expected, or None."""
    parts = urlsplit(target)
    want = f"{expected['method']} {expected['path']}"
    if method != expected["method"] or parts.path != expected["path"]:
        return f"sent {method} {parts.path}, expected {want}"

    query = parse_qsl(parts.query, keep_blank_values=True)
    if len(query) != len(dict(query)) or dict(query) != expected.get("query", {}):
        return f"{want}: query {parts.query!r}, expected {expected.get('query', {})}"

    if "contentType" in expected:
        sent = (headers.get("Content-Type") or "").split(";")[0].strip()
        if sent != expected["contentType"]:
            return f"{want}: Content-Type {sent!r}, expected {expected['contentType']!r}"
    if "accept" in expected and expected["accept"] not in (headers.get("Accept") or ""):
        return f"{want}: Accept {headers.get('Accept')!r} does not name {expected['accept']!r}"

    if "body" in expected:
        want_body = expected["body"]
        if isinstance(want_body, str):
            if body.decode() != want_body:
                return f"{want}: body {body.decode()!r}, expected {want_body!r}"
        else:
            try:
                sent_body = json.loads(body)
            except ValueError:
                return f"{want}: body is not JSON: {body!r}"
            if isinstance(want_body, dict):
                if not isinstance(sent_body, dict):
                    return f"{want}: body {sent_body!r} is not an object"
                wrong = {k: sent_body.get(k, "<absent>") for k, v in want_body.items()
                         if k not in sent_body or sent_body[k] != v}
                if wrong:
                    return f"{want}: body fields {wrong}, expected {want_body}"
            elif sent_body != want_body:
                return f"{want}: body {sent_body!r}, expected {want_body!r}"
    return None


class _Loopback:
    """The case's requests, answered in order; every miss recorded."""

    def __init__(self, case: dict[str, Any]):
        self.expected = list(case["requests"])
        self.served = 0
        self.failures: list[str] = []
        loopback = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # keep the test output clean
                pass

            def _reply(self, status: int, body: Any, content_type: str) -> None:
                if body is None:
                    payload = b""
                elif isinstance(body, str) and content_type != "application/json":
                    payload = body.encode()
                else:
                    payload = json.dumps(body).encode()
                self.send_response(status)
                if payload:
                    self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def _answer(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                if urlsplit(self.path).path.startswith("/api/tokens"):
                    self._reply(200, SIGNED_IN, "application/json")
                    return
                if loopback.served >= len(loopback.expected):
                    loopback.failures.append(f"unexpected {self.command} {self.path}")
                    self._reply(599, {"message": "not in the case"}, "application/json")
                    return
                expected = loopback.expected[loopback.served]
                loopback.served += 1
                wrong = _mismatch(expected, self.command, self.path, self.headers, body)
                if wrong is not None:
                    loopback.failures.append(wrong)
                    self._reply(599, {"message": wrong}, "application/json")
                    return
                response = expected["response"]
                self._reply(response["status"], response.get("body"),
                            response.get("contentType", "application/json"))

            do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _answer

        self.httpd = HTTPServer(("127.0.0.1", 0), Handler)

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.httpd.server_address[1]}/api"


def _case_id(case: dict[str, Any]) -> str:
    args = ",".join(f"{k}={v}" for k, v in case["args"].items() if k != "csv")
    return f"{case['call']}({args})"


@pytest.mark.parametrize("case", CASES, ids=[_case_id(c) for c in CASES])
def test_route(case: dict[str, Any], tmp_path: Path) -> None:
    loopback = _Loopback(case)
    threading.Thread(target=loopback.httpd.serve_forever, args=(0.01,), daemon=True).start()
    try:
        with Flexemarkets.connect(TOKEN, f"{loopback.base}/marketplaces/1", "route-test") as fm:
            error: BaseException | None = None
            try:
                result = _call(fm, case, tmp_path)
            except Exception as e:  # reported with the request failures below
                error = e
    finally:
        loopback.httpd.shutdown()
        loopback.httpd.server_close()

    assert not loopback.failures, loopback.failures
    assert error is None, f"{case['call']} raised {error!r}"
    assert loopback.served == len(loopback.expected), (
        f"sent {loopback.served} of {len(loopback.expected)} requests")
    if "returns" in case:
        if isinstance(case["returns"], bool):
            assert result is case["returns"]
        else:
            assert result == case["returns"]
