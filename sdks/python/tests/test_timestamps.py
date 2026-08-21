"""The server spells a moment two ways, and one of them is a trap.

Audit fields arrive bare -- ``"2017-04-11T00:54:35.135"`` -- because they come
from a Java LocalDateTime. ``expiresAt`` arrives ``"2026-08-15T18:00:00Z"``
because it comes from an Instant. Both sampled from api.flexemarkets.com.

A bare timestamp is UTC: the server's clock is. ``fromisoformat`` parses it and
leaves it *naive*, which is not wrong so much as unanswerable -- a naive
datetime raises when compared with an aware one, and ``.timestamp()`` on it
quietly assumes local time. So the assumption is made explicit and every value
is aware.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

from fm.client import _parse_order, _parse_session
from fm.timestamps import parse


def test_a_bare_timestamp_is_read_as_utc():
    assert parse("2017-04-11T00:54:35.135") == datetime(
        2017, 4, 11, 0, 54, 35, 135000, tzinfo=UTC)


def test_fractional_precision_varies():
    assert parse("2026-05-16T07:44:50.804552").microsecond == 804552
    assert parse("2026-05-16T07:44:50").second == 50


def test_a_zoned_timestamp_is_taken_as_given():
    assert parse("2026-08-15T18:00:00Z") == datetime(2026, 8, 15, 18, tzinfo=UTC)


def test_an_offset_is_honoured_not_shifted_again():
    assert parse("2026-08-15T19:00:00+01:00") == datetime(2026, 8, 15, 18, tzinfo=UTC)


def test_everything_returned_is_timezone_aware():
    """The point of the exercise: a naive result would raise on comparison."""
    for value in ("2017-04-11T00:54:35.135", "2026-08-15T18:00:00Z"):
        assert parse(value).tzinfo is not None
        # would raise TypeError if naive
        assert parse(value) > datetime(2000, 1, 1, tzinfo=timezone(timedelta(hours=-7)))


def test_an_unreadable_value_is_none_rather_than_a_raise():
    assert parse("not a date") is None
    assert parse("") is None
    assert parse("   ") is None
    assert parse(None) is None


# --- and that the parsing is actually wired in ------------------------------

def test_an_order_off_the_wire_carries_datetimes():
    order = _parse_order({"id": 1, "createdDate": "2026-05-16T07:44:50.804552"})

    assert order.created_date == datetime(2026, 5, 16, 7, 44, 50, 804552, tzinfo=UTC)


def test_a_session_off_the_wire_carries_datetimes():
    session = _parse_session({"id": 300, "openDate": "2026-08-15T09:00:00.5"})

    assert session.open_date == datetime(2026, 8, 15, 9, 0, 0, 500000, tzinfo=UTC)
    assert session.close_date is None
