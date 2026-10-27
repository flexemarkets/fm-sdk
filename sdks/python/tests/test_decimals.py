"""Decimals, held to the cases all three SDKs run.

See sdks/fixtures/decimals/README.md.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest

from fm import Decimals

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures" / "decimals"


def _cases(name: str) -> list[dict]:
    return json.loads((FIXTURES / name).read_text())["cases"]


def _id(case: dict) -> str:
    return f"{case.get('reason', case['text'])}@{case['decimals']}"


@pytest.mark.parametrize("case", _cases("format.json"), ids=_id)
def test_formats_as_the_shared_fixtures_say(case: dict) -> None:
    assert Decimals.format(case["value"], case["decimals"]) == case["text"]
    assert Decimals.parse(case["text"], case["decimals"]) == case["value"]


@pytest.mark.parametrize("case", _cases("parse.json"), ids=_id)
def test_parses_as_the_shared_fixtures_say(case: dict) -> None:
    assert Decimals.parse(case["text"], case["decimals"]) == case["value"]


@pytest.mark.parametrize("case", _cases("refused.json"), ids=_id)
def test_refuses_what_the_shared_fixtures_refuse(case: dict) -> None:
    with pytest.raises(ValueError):
        Decimals.parse(case["text"], case["decimals"])


def test_there_are_fixtures_to_run() -> None:
    """Guard the guard: a bad path would report everything passing."""
    for name in ("format.json", "parse.json", "refused.json"):
        assert len(_cases(name)) >= 10, name


def test_the_whole_long_range_round_trips() -> None:
    """The range Java reads, which TypeScript cannot."""
    assert Decimals.format(2**63 - 1, 2) == "92233720368547758.07"
    assert Decimals.format(-(2**63), 2) == "-92233720368547758.08"
    assert Decimals.parse("92233720368547758.07", 2) == 2**63 - 1
    assert Decimals.parse("-92233720368547758.08", 2) == -(2**63)
    with pytest.raises(ValueError, match="out of range"):
        Decimals.parse("92233720368547758.08", 2)


def test_says_why_it_refused() -> None:
    with pytest.raises(ValueError, match="more than 2 decimal places"):
        Decimals.parse("50.001", 2)
    with pytest.raises(ValueError, match="not a decimal number"):
        Decimals.parse("1e2", 2)


def test_refuses_what_has_already_lost_exactness() -> None:
    """A float is the thing this exists to keep out."""
    with pytest.raises(TypeError):
        Decimals.format(50.0, 2)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        Decimals.format(True, 2)  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="not a decimal number"):
        Decimals.parse(50, 2)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        Decimals.format(1, 2.0)  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        Decimals.format(1, -1)


def test_is_not_instantiated() -> None:
    with pytest.raises(TypeError):
        Decimals()


def test_agrees_with_the_cents_display_it_replaced() -> None:
    """The displays moved onto format printed f"{cents / 100:5.2f}". Over the
    range a price takes the two agree character for character, so the move
    changes no output."""
    for cents in range(-20_000, 500_001):
        old = f"{cents / 100:5.2f}"
        assert Decimals.format(cents, Decimals.DEFAULT_PRICE_DECIMALS).rjust(5) == old, cents


def test_the_ticker_prints_what_it_printed() -> None:
    spec = importlib.util.spec_from_file_location(
        "ticker", Path(__file__).resolve().parents[1] / "ticker.py")
    ticker = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(ticker)  # type: ignore[union-attr]

    assert ticker._price(-1) == "     -"
    assert ticker._price(5) == "$ 0.05"
    assert ticker._price(5000) == "$50.00"
    assert ticker._price(123456) == "$1234.56"
    assert ticker._spread(-1, 100) == "     -"
    assert ticker._spread(4950, 5000) == "$ 0.50"
    assert ticker._trade_prices([100, 205, 5000], 2) == "$50.00  $2.05"
