"""Signing in with a password, and what a connection says about who it is.

test_token_authentication covers a token; test_failure_messages a refused
password. A password that is *accepted* -- what every credential file does --
ran in no test, nor did the configuration a caller can get wrong before
anything reaches the server: no account, no email, no password, or a
credential that is neither a file nor a token.

Against a loopback server, as the other connection tests are, so the request
asserted is the one that went out.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any

import pytest

from fm.client import Flexemarkets
from fm.exceptions import AccountNameConflictError, ConfigurationError, PersonHasMarketplaceDataError

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"

SIGNED_IN: dict[str, Any] = {
    "token": TOKEN,
    "person": {"id": 7, "accountId": 3, "email": "dev@dev", "roles": ["ROLE_MANAGER"]},
    "account": {"id": 3, "name": "dev"},
}

# What went out: "METHOD path" and, for a body, the body as sent.
requests: list[str] = []
bodies: list[Any] = []
# path -> (status, body); a path not named answers SIGNED_IN.
answers: dict[str, tuple[int, Any]] = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args: Any) -> None:
        pass

    def _answer(self) -> None:
        requests.append(f"{self.command} {self.path}")
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            bodies.append(json.loads(self.rfile.read(length)))
        status, payload = answers.get(self.path, (200, SIGNED_IN))
        body = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_DELETE = _answer


@pytest.fixture
def base() -> str:
    requests.clear()
    bodies.clear()
    answers.clear()
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}/api"
    httpd.shutdown()


def _credential(tmp_path: Path, text: str) -> str:
    path = tmp_path / "credential"
    path.write_text(text)
    return str(path)


def test_a_password_signs_in_as_account_and_email_together(base: str, tmp_path: Path) -> None:
    credential = _credential(tmp_path, "account=dev\nemail=dev@dev\npassword=secret\n")

    with Flexemarkets(credential=credential, endpoint=f"{base}/marketplaces/12") as fm:
        assert requests == ["POST /api/tokens"]
        assert bodies == [{"username": "dev|dev@dev", "password": "secret"}]
        assert fm.token().token == TOKEN
        assert (fm.account_id, fm.account_name) == (3, "dev")
        assert fm.user_id == 7
        assert fm.endpoint_url == f"{base}/marketplaces/12"
        assert fm.endpoint_marketplace_id == 12


def test_an_account_and_email_given_outright_override_the_credential_file(base: str, tmp_path: Path) -> None:
    credential = _credential(tmp_path, "account=dev\nemail=dev@dev\npassword=secret\n")

    with Flexemarkets(credential=credential, endpoint=f"{base}/marketplaces/12",
                      account="other", email="other@dev"):
        assert bodies == [{"username": "other|other@dev", "password": "secret"}]


def test_an_endpoint_given_as_a_file_is_read_as_one(base: str, tmp_path: Path) -> None:
    endpoint = tmp_path / "endpoint"
    endpoint.write_text(f"endpoint={base}/marketplaces/12\n")

    with Flexemarkets(token=TOKEN, endpoint=str(endpoint)) as fm:
        assert fm.endpoint_url == f"{base}/marketplaces/12"
        assert requests == ["POST /api/tokens/refresh"]


@pytest.mark.parametrize("text, missing", [
    ("email=dev@dev\npassword=secret\n", "account"),
    ("account=dev\npassword=secret\n", "email"),
    ("account=dev\nemail=dev@dev\n", "password"),
])
def test_a_credential_missing_a_part_is_refused_before_anything_is_sent(
        base: str, tmp_path: Path, text: str, missing: str) -> None:
    with pytest.raises(ConfigurationError, match=f"Missing '{missing}' in configuration."):
        Flexemarkets(credential=_credential(tmp_path, text), endpoint=f"{base}/marketplaces/12")

    assert requests == []


def test_a_credential_that_is_neither_a_file_nor_a_token_is_refused(base: str) -> None:
    with pytest.raises(ConfigurationError, match="Invalid credential: 'no-such-file' is not a file or token."):
        Flexemarkets(credential="no-such-file", endpoint=f"{base}/marketplaces/12")

    assert requests == []


def test_roles_come_from_the_person_signed_in(base: str) -> None:
    with Flexemarkets(token=TOKEN, endpoint=f"{base}/marketplaces/12") as fm:
        assert fm.is_manager() is True
        assert fm.is_admin() is False
        assert fm.has_role("ROLE_MANAGER") is True


def test_a_person_without_roles_has_none(base: str) -> None:
    answers["/api/tokens/refresh"] = (200, {**SIGNED_IN, "person": {"id": 7, "accountId": 3}})

    with Flexemarkets(token=TOKEN, endpoint=f"{base}/marketplaces/12") as fm:
        assert fm.is_manager() is False
        assert fm.has_role("ROLE_TRADER") is False


def test_a_sign_in_answered_without_an_account_has_none(base: str) -> None:
    answers["/api/tokens/refresh"] = (200, {"token": TOKEN, "person": SIGNED_IN["person"]})

    with Flexemarkets(token=TOKEN, endpoint=f"{base}/marketplaces/12") as fm:
        assert fm.account is None
        assert fm.user_id == 7


# --- the two conflicts that carry their own type ------------------------------

def test_signing_up_sends_the_owners_names_when_given(base: str) -> None:
    with Flexemarkets(token=TOKEN, endpoint=f"{base}/marketplaces/12") as fm:
        fm.signup("acme", "owner@acme", "pw", first_name="Ada", last_name="Lovelace")
        fm.signup("acme2", "owner@acme", "pw")

    assert bodies == [
        {"accountName": "acme", "ownerEmail": "owner@acme", "ownerPassword": "pw",
         "firstName": "Ada", "lastName": "Lovelace"},
        {"accountName": "acme2", "ownerEmail": "owner@acme", "ownerPassword": "pw"},
    ]


def test_a_taken_account_name_carries_the_servers_suggestion(base: str) -> None:
    answers["/api/v1/accounts"] = (409, {"status": "CONFLICT", "suggestedName": "acme-2"})

    with Flexemarkets(token=TOKEN, endpoint=f"{base}/marketplaces/12") as fm:
        with pytest.raises(AccountNameConflictError, match="Account name 'acme' is already taken.") as e:
            fm.signup("acme", "owner@acme", "pw")

    assert e.value.suggested_name == "acme-2"


def test_a_taken_account_name_without_a_readable_answer_suggests_nothing(base: str) -> None:
    answers["/api/v1/accounts"] = (409, "<html>conflict</html>")

    with Flexemarkets(token=TOKEN, endpoint=f"{base}/marketplaces/12") as fm:
        with pytest.raises(AccountNameConflictError) as e:
            fm.signup("acme", "owner@acme", "pw")

    assert e.value.suggested_name is None


def test_a_user_with_marketplace_data_cannot_be_deleted(base: str) -> None:
    answers["/api/v1/users/44"] = (409, {"status": "CONFLICT"})

    with Flexemarkets(token=TOKEN, endpoint=f"{base}/marketplaces/12") as fm:
        with pytest.raises(PersonHasMarketplaceDataError,
                           match="User 44 has marketplace data and cannot be deleted."):
            fm.delete_user(44)
