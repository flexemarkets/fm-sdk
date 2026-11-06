"""What a caller meets when the exchange never completes.

A caller catching ``FlexemarketsError`` is told that is every failure the SDK
raises. An ``httpx`` transport error escaped past it, from every call: a robot
guarding its order loop that way ended on the first connection reset. Mirrors the
Java SDK's TransportFailureTest, which has always answered ``ApiException``.
"""

from __future__ import annotations

import re
from pathlib import Path

import httpx
import pytest

import fm.client as client_module
from fm.client import Flexemarkets
from fm.exceptions import ApiError

TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl"
SIGNED_IN = {"token": TOKEN, "person": {"id": 7, "accountId": 1, "email": "dev@dev"},
             "account": {"id": 1, "name": "dev"}}


def _client(monkeypatch, tmp_path, answer) -> Flexemarkets:
    """A real client whose every request, sign-in aside, goes to ``answer``."""
    def route(request: httpx.Request) -> httpx.Response:
        if "/tokens" in request.url.path:
            return httpx.Response(200, json=SIGNED_IN)
        return answer(request)

    return _connect(monkeypatch, tmp_path, route)


def _connect(monkeypatch, tmp_path, route) -> Flexemarkets:
    monkeypatch.setattr("httpx._client.HTTPTransport", lambda **kwargs: httpx.MockTransport(route))
    path = tmp_path / "credential"
    path.write_text("account=dev\nemail=dev@dev\npassword=secret\n")
    return Flexemarkets.connect(str(path), "https://fm.test/api/marketplaces/1", "transport-test")


def _losing(error: Exception):
    def answer(request: httpx.Request) -> httpx.Response:
        raise error
    return answer


CALLS = {
    "submit_limit": lambda c: c.submit_limit(1, 11, "BUY", 1, 100),
    "submit_cancel": lambda c: c.submit_cancel(1, 11, 99),
    "markets": lambda c: c.markets(1),
    "active_orders": lambda c: c.active_orders(1),
    "recent_trades": lambda c: c.recent_trades(1),
    "download_holdings": lambda c: c.download_holdings(1),
    "delete_user": lambda c: c.delete_user(9),
    "remove_widget": lambda c: c.remove_widget(1, "layout"),
    "csv_upload": lambda c: c.upload_holdings_csv(1, "a,b\n"),
}


@pytest.mark.parametrize("error", [httpx.ConnectError("no route"), httpx.ReadTimeout("slow"),
                                   httpx.RemoteProtocolError("closed mid-answer")],
                         ids=["refused", "timed-out", "cut-off"])
@pytest.mark.parametrize("call", CALLS.values(), ids=CALLS.keys())
def test_a_lost_exchange_is_an_api_error_carrying_what_happened(monkeypatch, tmp_path, call, error):
    with _client(monkeypatch, tmp_path, _losing(error)) as client:
        with pytest.raises(ApiError) as lost:
            call(client)

    assert lost.value.__cause__ is error
    assert str(error) in str(lost.value)


def test_a_sign_in_that_cannot_reach_the_server_is_an_api_error_too(monkeypatch, tmp_path):
    with pytest.raises(ApiError):
        _connect(monkeypatch, tmp_path, _losing(httpx.ConnectError("no route")))


def test_an_order_is_sent_once_more_when_only_its_first_answer_was_lost(monkeypatch, tmp_path):
    """The resend has to survive the error changing type: it looks through ApiError
    to what is underneath, or an order whose answer was lost stops being resent."""
    seen: list[bytes] = []

    def answer(request: httpx.Request) -> httpx.Response:
        seen.append(request.content)
        if len(seen) == 1:
            raise httpx.ConnectError("no route")
        return httpx.Response(200, json={"id": 42, "marketplaceId": 1, "marketId": 11, "type": "LIMIT"})

    with _client(monkeypatch, tmp_path, answer) as client:
        assert client.submit_limit(1, 11, "BUY", 1, 100).id == 42
    assert len(seen) == 2 and seen[0] == seen[1]


def test_an_unreadable_answer_is_not_sent_again(monkeypatch, tmp_path):
    """Also an ApiError, but the server did answer, so the order is placed."""
    seen: list[httpx.Request] = []

    def answer(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, text="<html>not json</html>")

    with _client(monkeypatch, tmp_path, answer) as client:
        with pytest.raises(ApiError):
            client.submit_limit(1, 11, "BUY", 1, 100)
    assert len(seen) == 1


def test_nothing_sends_around_the_one_place_that_catches():
    """A call sending through httpx directly would let its error past, as all of them did."""
    source = Path(client_module.__file__).read_text()
    assert re.findall(r"self\._http\.(\w+)", source) == ["request", "close"]
