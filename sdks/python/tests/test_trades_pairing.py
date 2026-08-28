"""The trade tape: which side of a match it names, and what order it holds.

Both properties failed silently before ``Trade`` existed, which is why they are
asserted against a shared fixture rather than left to reading the code:

* the tape kept the *resting* order of each pair and dropped the incoming one,
  so a caller asking who took a trade got the maker -- a real participant, at a
  real price, in an answer with nothing wrong on its face;
* it appended in the order the array arrived, and the
  ``/v1/orders/recent-trades`` snapshot that seeds it -- on open, on a sequence
  gap, and after every reconnect -- arrives newest *first*, so the tape was
  backwards and ``most_recent_trades()[-1]`` was the oldest trade retained.

``sdks/fixtures/trades/pairing.json`` is the same fixture the Java and
TypeScript suites read, so the three cannot drift on the answer.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from fm import client as rest
from fm.trades import Trades
from fm.types import Market

FIXTURE = json.loads(
    (Path(__file__).resolve().parents[2] / "fixtures" / "trades" / "pairing.json").read_text()
)


def _tape() -> Trades:
    market = Market(id=FIXTURE["market"]["id"], symbol=FIXTURE["market"]["symbol"])
    trades = Trades(market, 100)
    trades.update([rest._parse_order(o) for o in FIXTURE["orders"]])
    return trades


def test_the_fixture_is_delivered_newest_first() -> None:
    """Guard the guard: if the fixture already arrived oldest-first, the
    ordering assertion below would pass on a tape that never sorted anything.

    The claim is about the *pairs*, not the rows -- within a pair the resting
    order deliberately carries the later stamp, so that reading the time off
    the wrong side is visible in :func:`test_the_time_comes_from_the_aggressor`.
    """
    ids = [o["id"] for o in FIXTURE["orders"]]
    positions = [ids.index(e["aggressorId"]) for e in FIXTURE["expect"]]

    assert positions == sorted(positions, reverse=True), (
        "the fixture must deliver the newer trade's pair first, the way the "
        f"recent-trades snapshot does; aggressors sit at {positions}"
    )


def test_tape_names_the_aggressor_not_the_resting_order() -> None:
    tape = _tape()
    actual = tape.most_recent_trades()

    assert len(actual) == len(FIXTURE["expect"])

    for trade, expected in zip(actual, FIXTURE["expect"]):
        where = f"trade at {expected['price']}"
        assert trade.aggressor.owner_id == expected["aggressorOwnerId"], (
            f"{where}: named owner {trade.aggressor.owner_id} as the aggressor, "
            f"expected {expected['aggressorOwnerId']} -- "
            f"{expected['restingOwnerId']} is the resting side"
        )
        assert trade.resting.owner_id == expected["restingOwnerId"], where
        assert trade.aggressor.id == expected["aggressorId"], where
        assert trade.resting.id == expected["restingId"], where


def test_price_and_units_come_from_the_resting_side() -> None:
    for trade, expected in zip(_tape().most_recent_trades(), FIXTURE["expect"]):
        assert trade.price == expected["price"]
        assert trade.units == expected["units"]
        assert trade.price == trade.resting.price


def test_the_time_comes_from_the_aggressor() -> None:
    """The trade happened when the incoming order arrived, not when the quote
    it took was posted. Each resting order in the fixture carries a *later*
    stamp than its aggressor, so reading the wrong side is visible here."""
    for trade, expected in zip(_tape().most_recent_trades(), FIXTURE["expect"]):
        assert trade.at is not None
        assert int(trade.at.timestamp() * 1000) == expected["at"]["epochMilli"], (
            f"expected {datetime.fromtimestamp(expected['at']['epochMilli'] / 1000, timezone.utc)}, "
            f"got {trade.at} -- that is the resting order's stamp"
        )


def test_tape_is_oldest_first_even_when_the_snapshot_is_not() -> None:
    tape = _tape()
    prices = tape.most_recent_prices()

    assert prices == [e["price"] for e in FIXTURE["expect"]]
    assert tape.last() is not None
    assert tape.last().price == FIXTURE["expect"][-1]["price"], (
        "last() must be the newest trade; a tape that appends in array order "
        "returns the oldest one it retained"
    )


def test_empty_tape_has_no_last() -> None:
    market = Market(id=FIXTURE["market"]["id"], symbol=FIXTURE["market"]["symbol"])
    assert Trades(market, 100).last() is None
