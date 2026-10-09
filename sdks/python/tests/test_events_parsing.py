"""The frame codec and the event parser, without a socket.

test_events_socket drives these through a real WebSocket, with the frames a
server actually sends. What it cannot reach cheaply is every message type, and
every malformed or missing header a frame can carry -- which is what a parser
is for, since the well-formed case parses itself.
"""

from __future__ import annotations

import pytest

from fm.events import (
    NO_SEQ,
    OrdersUpdate,
    StompFrame,
    _decode_frame,
    _is_auth_refusal,
    _parse_event,
    _resolve_api_version_prefix,
)
from fm.types import Session, Version


def _frame(msg_type: str | None, body: str = "", **headers: str) -> StompFrame:
    if msg_type is not None:
        headers["message-type"] = msg_type
    return StompFrame(command="MESSAGE", headers=headers, body=body)


class TestApiVersionPrefix:
    def test_v1_is_the_default(self, monkeypatch):
        monkeypatch.delenv("FM_WS_API_VERSION", raising=False)
        assert _resolve_api_version_prefix() == "/v1"

    def test_v0_is_the_bare_destination_whatever_its_case(self, monkeypatch):
        monkeypatch.setenv("FM_WS_API_VERSION", " V0 ")
        assert _resolve_api_version_prefix() == ""

    def test_anything_else_is_refused_by_name(self, monkeypatch):
        monkeypatch.setenv("FM_WS_API_VERSION", "v2")
        with pytest.raises(ValueError, match="must be 'v0' or 'v1', got: v2"):
            _resolve_api_version_prefix()


class TestDecodeFrame:
    def test_a_frame_without_a_blank_line_is_all_headers_and_no_body(self):
        frame = _decode_frame("RECEIPT\nreceipt-id:77\0")

        assert frame.command == "RECEIPT"
        assert frame.headers == {"receipt-id": "77"}
        assert frame.body == ""

    def test_a_header_value_keeps_its_own_colons(self):
        frame = _decode_frame("MESSAGE\ndestination:/a:b\n\n{}\0")

        assert frame.headers == {"destination": "/a:b"}
        assert frame.body == "{}"


class TestParseEvent:
    def test_a_frame_without_a_message_type_is_nothing(self):
        assert _parse_event(_frame(None, "{}")) is None

    def test_an_unknown_message_type_is_nothing(self):
        assert _parse_event(_frame("WIDGET-UPDATE", "{}")) is None

    def test_a_version_arrives_as_an_object_or_a_bare_number(self):
        assert _parse_event(_frame("VERSION", '{"version": 12}')) == Version(version=12)
        assert _parse_event(_frame("VERSION", "12")) == Version(version=12)
        assert _parse_event(_frame("VERSION", "")) == Version(version=0)

    def test_a_session_list_is_every_session_in_it(self):
        parsed = _parse_event(_frame(
            "SESSION-LIST", '[{"id": 30, "state": "OPEN"}, {"id": 31, "state": "PAUSED"}]'))

        assert [(s.id, s.state) for s in parsed] == [(30, "OPEN"), (31, "PAUSED")]

    def test_a_session_list_that_is_not_a_list_is_empty(self):
        assert _parse_event(_frame("SESSION-LIST", '{"id": 30}')) == []

    def test_a_session_update_is_one_session(self):
        parsed = _parse_event(_frame("SESSION-UPDATE", '{"id": 30, "marketplaceId": 7, "state": "CLOSED"}'))

        assert isinstance(parsed, Session)
        assert (parsed.id, parsed.marketplace_id, parsed.state) == (30, 7, "CLOSED")

    def test_an_orders_update_without_a_seq_header_has_no_seq(self):
        parsed = _parse_event(_frame("ORDERS-UPDATE", "[]"))

        assert parsed == OrdersUpdate(orders=[], seq=NO_SEQ)

    def test_an_orders_update_that_is_not_a_list_has_no_orders(self):
        parsed = _parse_event(_frame("ORDERS-UPDATE", '{"id": 5}', seq="9"))

        assert parsed == OrdersUpdate(orders=[], seq=9)


class _Refused(Exception):
    def __init__(self, status: int) -> None:
        super().__init__(f"HTTP {status}")
        self.response = type("Response", (), {"status_code": status})()


class TestIsAuthRefusal:
    def test_401_and_403_are_refusals_and_a_server_error_is_not(self):
        assert _is_auth_refusal(_Refused(401)) is True
        assert _is_auth_refusal(_Refused(403)) is True
        assert _is_auth_refusal(_Refused(500)) is False
        assert _is_auth_refusal(OSError("connection reset")) is False

    def test_a_refusal_is_found_under_whatever_wraps_it(self):
        try:
            try:
                raise _Refused(401)
            except _Refused as refused:
                raise OSError("handshake failed") from refused
        except OSError as wrapped:
            assert _is_auth_refusal(wrapped) is True

    def test_a_cycle_of_causes_ends_rather_than_looping(self):
        first, second = OSError("a"), OSError("b")
        first.__cause__, second.__cause__ = second, first

        assert _is_auth_refusal(first) is False
