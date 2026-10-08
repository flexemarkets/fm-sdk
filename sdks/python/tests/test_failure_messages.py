"""What a caller meets when the server says no, and when a desk's seed comes
back. Mirrors the Java SDK's HttpFailureMappingTest and
SignInAndSnapshotFailureTest.

The two SDKs had drifted. Java reported the server's sentence -- "Authentication
failed: Wrong password." -- while this one said "Authentication failed." to
every refused sign-in, whatever the reason, and put the whole JSON envelope
into every other refusal.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any

import pytest

from fm.client import Flexemarkets
from fm.exceptions import (
    ApiError,
    AuthenticationError,
    AuthorizationError,
    ConflictError,
    ConnectionFailedError,
    FlexemarketsError,
    HttpError,
    InvalidArgumentError,
)
from fm.snapshot import NO_SEQ

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"
SIGNED_IN = {"token": TOKEN, "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
             "account": {"id": 1, "name": "dev"}}

# path -> (status, body, x-fm-as-of-seq or None); anything else answers ANSWER,
# which until a test sets it is a 404: a request on a path no test named
# fails rather than reading as an empty success.
answers: dict[str, tuple[int, Any, str | None]] = {}
UNANSWERED = (404, {"error": "NOT_FOUND", "message": "no route", "status": 404})
ANSWER: list[tuple[int, Any]] = [UNANSWERED]


def _refusal(error: str, message: str, status: int) -> dict[str, Any]:
    return {"error": error, "message": message, "path": "/api/x", "shortDigest": "abc123", "status": status}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args: Any) -> None:
        pass

    def _answer(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        path = self.path.split("?")[0]
        status, payload, seq = answers.get(path, (*ANSWER[0], None))
        body = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if seq is not None:
            self.send_header("x-fm-as-of-seq", seq)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = _answer


@pytest.fixture
def base() -> str:
    answers.clear()
    answers["/api/tokens"] = (200, SIGNED_IN, None)
    answers["/api/tokens/refresh"] = (200, SIGNED_IN, None)
    ANSWER[0] = UNANSWERED
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}/api"
    httpd.shutdown()


def _connect(base: str) -> Flexemarkets:
    return Flexemarkets.connect(TOKEN, f"{base}/marketplaces/1", "failure-test")


def _call(base: str, status: int, body: Any) -> None:
    fm = _connect(base)
    ANSWER[0] = (status, body)
    try:
        fm.active_orders(1)
    finally:
        fm.close()


# --- the mapping, as Java's HttpFailureMappingTest ---------------------------

def test_a_bad_request_is_an_invalid_argument_carrying_the_servers_sentence(base: str) -> None:
    with pytest.raises(InvalidArgumentError) as e:
        _call(base, 400, _refusal("ORDER_INVALID", "price off the tick", 400))
    assert str(e.value) == "Invalid request: price off the tick"


def test_a_forbidden_call_is_an_authorization_failure_carrying_the_servers_sentence(base: str) -> None:
    with pytest.raises(AuthorizationError) as e:
        _call(base, 403, _refusal("NOT_PERMITTED", "not permitted", 403))
    assert str(e.value) == "Not permitted: not permitted"


def test_a_refusal_that_is_not_json_is_reported_as_it_came(base: str) -> None:
    with pytest.raises(InvalidArgumentError) as e:
        _call(base, 400, "<html>edge</html>")
    assert str(e.value) == "Invalid request: <html>edge</html>"


def test_a_plain_409_is_a_conflict(base: str) -> None:
    with pytest.raises(ConflictError):
        _call(base, 409, {"status": "CONFLICT", "message": "taken"})


def test_a_server_error_is_a_connection_failure(base: str) -> None:
    with pytest.raises(ConnectionFailedError):
        _call(base, 503, "unavailable")


def test_anything_else_is_a_typed_http_error_carrying_its_status(base: str) -> None:
    with pytest.raises(HttpError) as e:
        _call(base, 404, _refusal("NOT_FOUND", "no such marketplace", 404))
    assert e.value.status_code == 404
    assert isinstance(e.value, FlexemarketsError)


# --- sign-in and snapshots, as Java's SignInAndSnapshotFailureTest ----------

def test_a_refused_password_is_an_authentication_failure_carrying_the_servers_sentence(base: str, tmp_path: Path) -> None:
    answers["/api/tokens"] = (401, _refusal("ACCOUNT_INVALID_CREDENTIALS", "Wrong password.", 401), None)
    credential = tmp_path / "credential"
    credential.write_text("account=dev\nemail=dev@dev\npassword=nope\n")

    with pytest.raises(AuthenticationError) as e:
        Flexemarkets.connect(str(credential), f"{base}/marketplaces/1", "failure-test")
    assert str(e.value) == "Authentication failed: Wrong password."


def test_a_refused_token_fails_at_connect_saying_why(base: str) -> None:
    answers["/api/tokens/refresh"] = (401, _refusal("TOKEN_EXPIRED", "Token expired.", 401), None)

    with pytest.raises(AuthenticationError) as e:
        _connect(base)
    assert str(e.value) == "Authentication failed: Token expired."


def test_a_snapshot_carries_the_sequence_it_was_taken_at(base: str) -> None:
    answers["/api/v1/marketplaces/1/orders"] = (200, [], "41")
    fm = _connect(base)
    try:
        snapshot = fm.active_orders(1)
        assert snapshot.as_of_seq == 41
        assert snapshot.body == []
    finally:
        fm.close()


def test_a_snapshot_without_a_sequence_says_so(base: str) -> None:
    """No header -- an older server -- is "no sequence", which a desk must not mistake for 0."""
    answers["/api/v1/marketplaces/1/orders"] = (200, [], None)
    fm = _connect(base)
    try:
        assert fm.active_orders(1).as_of_seq == NO_SEQ
    finally:
        fm.close()


def test_a_refused_snapshot_is_the_servers_refusal(base: str) -> None:
    answers["/api/v1/marketplaces/1/orders"] = (403, _refusal("NOT_PERMITTED", "Not your marketplace.", 403), None)
    fm = _connect(base)
    try:
        with pytest.raises(AuthorizationError) as e:
            fm.active_orders(1)
        assert str(e.value) == "Not permitted: Not your marketplace."
    finally:
        fm.close()


# --- an answer that arrives but does not parse --------------------------------

def test_an_order_answer_that_cannot_be_read_says_so(base: str) -> None:
    """As Java's anOrderAnswerThatCannotBeReadSaysSo: a 200 that is not JSON is
    an ApiError, not a json.JSONDecodeError escaping past FlexemarketsError."""
    answers["/api/v1/marketplaces/1/orders"] = (200, "<html>edge error page</html>", None)
    fm = _connect(base)
    try:
        with pytest.raises(ApiError) as e:
            fm.submit_limit(1, 11, "BUY", 1, 100)
        assert str(e.value) == "Failed to parse the response body"
        assert isinstance(e.value, FlexemarketsError)
    finally:
        fm.close()


def test_a_snapshot_that_cannot_be_read_says_so(base: str) -> None:
    answers["/api/v1/marketplaces/1/orders"] = (200, "<html>edge error page</html>", "41")
    fm = _connect(base)
    try:
        with pytest.raises(ApiError) as e:
            fm.active_orders(1)
        assert str(e.value) == "Failed to parse the response body"
    finally:
        fm.close()
