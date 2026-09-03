"""Every behaviour fixture, driven through this SDK's aggregators.

The wire fixtures next door compare *parsed field values*: one payload in, one
set of fields out. They say nothing about what Book and Tape do with a
sequence of them, which is where the three SDKs have actually been wrong
together -- a book that double-counts a cancel and a tape that holds its trades
backwards both parse every field correctly.

So these are inputs and answers rather than payloads and fields: a market, a
list of update steps, and what the aggregator must hold at the end. Java,
Python and TypeScript each run all of them, so a behaviour cannot be right in
one SDK and wrong in another without saying so.

See ``sdks/fixtures/README.md``.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from fm import client as rest
from fm.orderbook import Book
from fm.trades import Tape
from fm.types import Market

FIXTURES = sorted(
    (Path(__file__).resolve().parents[2] / "fixtures" / "behaviour").glob("*.json")
)

AGGREGATORS = {"Book": Book, "Tape": Tape}


def _drive(doc: dict[str, Any]) -> Any:
    market = Market(id=doc["market"]["id"], symbol=doc["market"]["symbol"])
    aggregator = AGGREGATORS[doc["type"]](market)

    for index, step in enumerate(doc["steps"]):
        if step.get("clear"):
            aggregator.clear()

        orders = [rest._parse_order(o) for o in step["orders"]]

        # A step that declares `refused` must raise rather than apply: an order that names no side cannot be placed on a book, and guessing one files it under the offer side silently. The step after it asserts the book was left alone.
        if "refused" in step:
            with pytest.raises(ValueError, match=re.escape(step["refused"])):
                aggregator.update(orders)
            continue

        added = aggregator.update(orders)

        # What update() reports it added is what Desk dispatches on_trade
        # from. A step that declares `adds` pins it -- including the zero, which
        # is the update a handler must stay silent through.
        if "adds" in step:
            assert len(added) == step["adds"], (
                f"step {index} ({step.get('note', '')!r}) reported "
                f"{len(added)} new trades, expected {step['adds']}"
            )

    return aggregator


def _epoch_milli(value: Any, where: str) -> int:
    assert value is not None, f"{where}: expected an instant, got None"
    assert isinstance(value, datetime), (
        f"{where}: expected a datetime, got {type(value).__name__} -- unconverted"
    )
    assert value.tzinfo is not None, (
        f"{where}: naive datetime. A bare server timestamp means UTC, and a naive "
        f"value takes the reader's local zone -- right only on a UTC machine."
    )
    return int(value.timestamp() * 1000)


def _check_trade(actual: Any, expected: dict[str, Any], where: str) -> None:
    readers = {
        "price": lambda t: t.price,
        "units": lambda t: t.units,
        "restingId": lambda t: t.resting.id,
        "aggressorId": lambda t: t.aggressor.id,
        "restingOwnerId": lambda t: t.resting.owner_id,
        "aggressorOwnerId": lambda t: t.aggressor.owner_id,
    }
    for key, want in expected.items():
        if key == "at":
            assert _epoch_milli(actual.at, f"{where}.at") == want["epochMilli"], (
                f"{where}.at is the wrong side's stamp -- the trade happened when "
                f"the aggressor arrived, not when the quote it took was posted"
            )
            continue
        assert key in readers, f"{where}: fixture asks for unknown key {key!r}"
        assert readers[key](actual) == want, f"{where}.{key}"


def _check_order_book(book: Book, expect: dict[str, Any]) -> None:
    readers = {
        "bestBuyPrice": book.best_buy_price,
        "bestBuyUnits": book.best_buy_units,
        "bestSellPrice": book.best_sell_price,
        "bestSellUnits": book.best_sell_units,
        "hasValueBuy": lambda: book.has_value("BUY"),
        "hasValueSell": lambda: book.has_value("SELL"),
        "buyLevels": lambda: [list(level) for level in book.buy_levels()],
        "sellLevels": lambda: [list(level) for level in book.sell_levels()],
    }
    for key, want in expect.items():
        assert key in readers, f"fixture asks for unknown key {key!r}"
        assert readers[key]() == want, key


def _check_trades(tape: Tape, expect: dict[str, Any]) -> None:
    held = tape.most_recent_trades()

    if "size" in expect:
        assert tape.size() == expect["size"]

    if "trades" in expect:
        assert len(held) == len(expect["trades"]), (
            f"tape holds {len(held)} trades, expected {len(expect['trades'])}"
        )
        for index, (actual, wanted) in enumerate(zip(held, expect["trades"])):
            _check_trade(actual, wanted, f"trades[{index}]")

    if "last" in expect:
        if expect["last"] is None:
            assert tape.last() is None
        else:
            assert tape.last() is not None, "last() is None but the tape is not empty"
            _check_trade(tape.last(), expect["last"], "last()")

    # Last, because it empties the tape.
    if "drain" in expect:
        drained = tape.drain()
        assert len(drained) == expect["drain"]["count"]
        assert tape.size() == expect["drain"]["sizeAfter"]
        assert tape.last() is None
        assert tape.drain() == []


CHECKS = {"Book": _check_order_book, "Tape": _check_trades}


@pytest.mark.parametrize("path", FIXTURES, ids=lambda p: p.stem)
def test_behaviour_fixture(path: Path) -> None:
    doc = json.loads(path.read_text())

    delivered = [o["id"] for step in doc["steps"] for o in step["orders"]]
    assert delivered == doc["deliveredIds"], (
        "the fixture's input is not in the order it declares. deliveredIds is "
        "what stops a case being quietly reordered into one that proves nothing "
        "-- the trades-ordering fixture is only a test at all because its input "
        "arrives newest first."
    )

    CHECKS[doc["type"]](_drive(doc), doc["expect"])


def test_there_are_fixtures_to_run() -> None:
    """Guard the guard: a bad glob would report everything passing."""
    assert len(FIXTURES) >= 4, f"only found {len(FIXTURES)} behaviour fixtures"


def test_every_fixture_type_has_an_aggregator() -> None:
    types = {json.loads(p.read_text())["type"] for p in FIXTURES}
    unmapped = types - set(AGGREGATORS)
    assert not unmapped, f"fixtures exist for {sorted(unmapped)}, which nothing here drives"
