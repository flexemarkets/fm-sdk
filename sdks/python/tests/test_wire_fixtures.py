"""Every shared fixture, through every parser that claims to produce its type.

See ``sdks/fixtures/README.md``. The short version: check-parity.py compares
the three SDKs' *declarations* and can only see field names, which is how
``approval`` stayed wrong in two of them while the check reported ok. These
compare values.

And they run each fixture through *both* of Python's parsers where there are
two. ``Session``, ``Holding`` and ``Order`` are parsed once for REST and once
for the WebSocket, and nothing held the pairs together -- ``events`` delegated
to ``client`` for two of the three and kept its own copy of the third.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import pytest

from fm import client as rest
from fm import events as ws

FIXTURES = sorted((Path(__file__).resolve().parents[2] / "fixtures").glob("*.json"))

# type -> the parsers that must all agree about it. Where a type is parsed on
# both the REST and the WebSocket path, both are listed by name, so a failure
# says which one.
PARSERS: dict[str, list[tuple[str, Callable[[dict[str, Any]], Any]]]] = {
    "Order": [("client", rest._parse_order), ("events", ws._parse_order)],
    "Session": [("client", rest._parse_session), ("events", ws._parse_session)],
    "Holding": [("client", rest._parse_holding), ("events", ws._parse_holding)],
    "Account": [("client", rest._parse_account)],
    "Person": [("client", rest._parse_person)],
    "Market": [("client", rest._parse_market)],
    "Marketplace": [("client", rest._parse_marketplace)],
    "ClientConnection": [("client", rest._parse_connection)],
    "Security": [("client", rest._parse_security)],
    "Token": [("client", rest._parse_token)],
}


def _snake(name: str) -> str:
    return "".join("_" + c.lower() if c.isupper() else c for c in name)


def _actual(parsed: Any, wire_name: str) -> Any:
    attr = _snake(wire_name)
    if not hasattr(parsed, attr):
        raise AssertionError(f"{type(parsed).__name__} has no field {attr!r} (wire {wire_name!r})")
    return getattr(parsed, attr)


def _check(parsed: Any, expected: Any, where: str) -> None:
    """Compare one expected value against what the parser produced."""
    if isinstance(expected, dict) and set(expected) == {"epochMilli"}:
        assert parsed is not None, f"{where}: expected an instant, got None"
        assert isinstance(parsed, datetime), (
            f"{where}: expected a datetime, got {type(parsed).__name__} {parsed!r} "
            f"-- the parser left the wire value unconverted"
        )
        assert parsed.tzinfo is not None, (
            f"{where}: naive datetime. A bare server timestamp means UTC, and a naive "
            f"value takes the reader's local zone -- right only on a UTC machine."
        )
        actual_ms = int(parsed.timestamp() * 1000)
        assert actual_ms == expected["epochMilli"], (
            f"{where}: {parsed.isoformat()} is {actual_ms}, expected {expected['epochMilli']} "
            f"({datetime.fromtimestamp(expected['epochMilli'] / 1000, timezone.utc).isoformat()})"
        )
        return

    if isinstance(expected, list):
        assert parsed is not None, f"{where}: expected a list, got None"
        assert len(parsed) == len(expected), (
            f"{where}: expected {len(expected)} items, got {len(parsed)}"
        )
        for i, (item, want) in enumerate(zip(parsed, expected)):
            if isinstance(want, dict):
                # A list of objects: compare the named fields of each.
                for key, value in want.items():
                    _check(_actual(item, key), value, f"{where}[{i}].{key}")
            else:
                # A list of scalars -- roles, for one.
                _check(item, want, f"{where}[{i}]")
        return

    if isinstance(expected, dict):
        for key, value in expected.items():
            _check(_actual(parsed, key), value, f"{where}.{key}")
        return

    # Enums are StrEnum, so a plain == would pass for the string form and hide a
    # parser that never converted. Compare the value, having checked the type.
    assert parsed == expected, f"{where}: expected {expected!r}, got {parsed!r}"


def _cases() -> list[tuple[str, str, str]]:
    out = []
    for path in FIXTURES:
        doc = json.loads(path.read_text())
        for parser_name, _ in PARSERS.get(doc["type"], []):
            out.append((path.stem, doc["type"], parser_name))
    return out


@pytest.mark.parametrize("fixture,type_name,parser_name", _cases())
def test_fixture(fixture: str, type_name: str, parser_name: str) -> None:
    doc = json.loads((FIXTURES[0].parent / f"{fixture}.json").read_text())
    parse = dict(PARSERS[type_name])[parser_name]

    parsed = parse(doc["payload"])

    assert parsed is not None, f"{fixture}: {parser_name} returned None"
    for key, expected in doc["expect"].items():
        _check(_actual(parsed, key), expected, f"{fixture}/{parser_name}.{key}")


def test_there_are_fixtures_to_run() -> None:
    """Guard the guard: a bad glob would report everything passing."""
    assert len(FIXTURES) >= 10, f"only found {len(FIXTURES)} fixtures"
    assert _cases(), "no fixture matched a parser"


def test_every_fixture_type_has_a_parser() -> None:
    """A fixture for a type nothing parses is a test that does not run.

    Adding one is meant to be a one-file change, and this is what makes the
    silent half of that visible.
    """
    unmapped = {
        json.loads(p.read_text())["type"]
        for p in FIXTURES
        if json.loads(p.read_text())["type"] not in PARSERS
    }
    assert not unmapped, f"fixtures exist for {sorted(unmapped)}, which no parser here handles"
