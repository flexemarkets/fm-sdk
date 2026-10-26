"""Fields a robot reads that the parsers used to drop.

FMClientLibrary's robots read every one of these, and a transport built on this
SDK cannot hand them on if they are not parsed. Each is None when the response
did not carry it -- "not said" is not "zero" or "false".
"""

from datetime import datetime, timezone

from fm import client as rest
from fm import events as ws

PARSERS = [rest, ws]


def test_an_order_says_whether_it_is_the_readers_own():
    for p in PARSERS:
        assert p._parse_order({"id": 1, "mine": True}).mine is True
        assert p._parse_order({"id": 1, "mine": False}).mine is False
        assert p._parse_order({"id": 1}).mine is None


def test_an_order_carried_into_a_later_session_names_the_first():
    for p in PARSERS:
        assert p._parse_order({"id": 1, "originalSessionId": 41}).original_session_id == 41
        assert p._parse_order({"id": 1}).original_session_id is None


def test_a_holding_carries_what_the_session_opened_with():
    payload = {"cash": 900, "initialCash": 1000,
               "assets": [{"marketId": 11, "units": 3, "initialUnits": 5}]}
    for p in PARSERS:
        holding = p._parse_holding(payload)
        assert holding.initial_cash == 1000
        assert holding.security(11).initial_units == 5


def test_a_holding_that_does_not_say_leaves_the_opening_values_unknown():
    for p in PARSERS:
        holding = p._parse_holding({"cash": 900, "assets": [{"marketId": 11, "units": 3}]})
        assert holding.initial_cash is None
        assert holding.security(11).initial_units is None


def test_a_session_carries_its_created_and_modified_dates():
    for p in PARSERS:
        session = p._parse_session({"id": 7, "createdDate": "2026-10-26T15:48:37.230899",
                                    "lastModifiedDate": "2026-10-26T15:53:52Z"})
        assert session.created_date == datetime(2026, 10, 26, 15, 48, 37, 230899, tzinfo=timezone.utc)
        assert session.last_modified_date == datetime(2026, 10, 26, 15, 53, 52, tzinfo=timezone.utc)
