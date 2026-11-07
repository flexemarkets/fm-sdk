"""What a caller meets when the exchange never completes.

A caller catching ``FlexemarketsError`` is told that is every failure the SDK
raises. An ``httpx`` transport error escaped past it, from every call: a robot
guarding its order loop that way ended on the first connection reset. Mirrors the
Java SDK's TransportFailureTest, which has always answered ``ApiException``.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import httpx
import pytest

import fm.client as client_module
from fm.client import Flexemarkets
from fm.exceptions import ApiError, ConnectionFailedError, FlexemarketsError
from fm.types import Limit

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
    assert re.findall(r"self\._http\.(\w+)", source) == ["is_closed", "request", "close"]


@pytest.mark.parametrize("call", CALLS.values(), ids=CALLS.keys())
def test_an_answer_that_cannot_be_decompressed_is_an_api_error(monkeypatch, tmp_path, call):
    """httpx.DecodingError is not a TransportError: it passed the catch, from every call."""
    def answer(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"Content-Encoding": "gzip"}, content=b"not gzip at all")

    with _client(monkeypatch, tmp_path, answer) as client:
        with pytest.raises(ApiError) as lost:
            call(client)
    assert isinstance(lost.value.__cause__, httpx.DecodingError)


def test_a_closed_client_fails_as_the_sdk_fails(monkeypatch, tmp_path):
    client = _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, json=[]))
    client.close()

    with pytest.raises(ConnectionFailedError):
        client.markets(1)


ONE_THING = {
    "submit_limit": lambda c: c.submit_limit(1, 11, "BUY", 1, 100),
    "submit_cancel": lambda c: c.submit_cancel(1, 11, 99),
    "marketplace": lambda c: c.marketplace(1),
    "session": lambda c: c.session(1),
    "holding": lambda c: c.holding(1),
}
A_LIST = {
    "markets": lambda c: c.markets(1),
    "accounts": lambda c: c.accounts(),
    "active_orders": lambda c: c.active_orders(1),
    "recent_trades": lambda c: c.recent_trades(1),
    "sessions": lambda c: c.sessions(1),
}


@pytest.mark.parametrize("body", [[], 1, "x", None], ids=["list", "number", "text", "null"])
@pytest.mark.parametrize("call", ONE_THING.values(), ids=ONE_THING.keys())
def test_valid_json_that_is_not_the_object_asked_for_is_an_api_error(monkeypatch, tmp_path, call, body):
    """A caller catching FlexemarketsError met AttributeError and TypeError instead."""
    with _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, content=json.dumps(body))) as client:
        with pytest.raises(ApiError):
            call(client)


def test_a_read_of_an_object_it_then_reaches_into_answered_with_the_wrong_shape_is_an_api_error(monkeypatch, tmp_path):
    with _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, content="1")) as client:
        with pytest.raises(ApiError):
            client.manager_otp_bundle([1])


@pytest.mark.parametrize("body", [{}, 1, "x", None, [1]], ids=["object", "number", "text", "null", "list-of-numbers"])
@pytest.mark.parametrize("call", A_LIST.values(), ids=A_LIST.keys())
def test_valid_json_that_is_not_the_list_asked_for_is_an_api_error(monkeypatch, tmp_path, call, body):
    with _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, content=json.dumps(body))) as client:
        with pytest.raises(ApiError):
            call(client)


@pytest.mark.parametrize("body", [[], 1, "x", None], ids=["list", "number", "text", "null"])
def test_a_sign_in_answered_with_the_wrong_shape_is_an_api_error(monkeypatch, tmp_path, body):
    with pytest.raises(ApiError):
        _connect(monkeypatch, tmp_path, lambda request: httpx.Response(200, content=json.dumps(body)))


def test_a_renewal_answered_with_the_wrong_shape_is_no_renewal(monkeypatch, tmp_path):
    """It leaked out of the reconnect thread, which died with nothing said."""
    sign_ins = {"n": 0}

    def route(request: httpx.Request) -> httpx.Response:
        if "/tokens" in request.url.path:
            sign_ins["n"] += 1
            return httpx.Response(200, json=SIGNED_IN if sign_ins["n"] == 1 else [])
        return httpx.Response(200, json=[])

    with _connect(monkeypatch, tmp_path, route) as client:
        assert client._renew_token(client._bearer_token()) is False


@pytest.mark.parametrize("name", sorted(n for n in vars(client_module) if n.startswith("_parse_")))
def test_every_parser_answers_a_wrong_shape_with_an_api_error(name):
    """So one added later cannot let AttributeError past a caller."""
    with pytest.raises(FlexemarketsError):
        getattr(client_module, name)(1)


def test_every_list_answer_is_checked_for_being_one():
    source = Path(client_module.__file__).read_text()
    assert not re.findall(r"for \w+ in (?:_json\(|data\])", source)


NO_ORDER_ID = [{}, {"side": "BUY", "units": 1, "price": 100}, {"id": None}, {"id": "80035520"}, {"id": 1.5}, {"id": True}]
NO_ORDER_ID_NAMES = ["empty", "no-id", "null-id", "text-id", "fraction-id", "boolean-id"]


@pytest.mark.parametrize("order", NO_ORDER_ID, ids=NO_ORDER_ID_NAMES)
@pytest.mark.parametrize("call", ["submit_limit", "submit_cancel"])
def test_an_order_answered_with_no_id_is_an_api_error(monkeypatch, tmp_path, call, order):
    """It came back as order 0, and a robot cancelled order 0 or kept it as resting."""
    with _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, json=order)) as client:
        with pytest.raises(ApiError, match="order with no id"):
            ONE_THING[call](client)


def test_a_row_with_no_order_id_is_an_api_error(monkeypatch, tmp_path):
    with _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, json=[{"id": 5}, {}])) as client:
        with pytest.raises(ApiError, match="order with no id"):
            client.active_orders(1)


def test_a_batch_entry_with_no_order_id_is_an_api_error(monkeypatch, tmp_path):
    answer = [{"status": 201, "order": {}}]
    with _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, json=answer)) as client:
        with pytest.raises(ApiError, match="order with no id"):
            client.submit_limits(1, [Limit(11, "BUY", 1, 100)])


def test_an_order_with_an_id_of_zero_is_still_read(monkeypatch, tmp_path):
    """The check is that the server named one, not which; a test double may use 0."""
    with _client(monkeypatch, tmp_path, lambda request: httpx.Response(200, json={"id": 0})) as client:
        assert client.submit_limit(1, 11, "BUY", 1, 100).id == 0
