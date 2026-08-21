"""Rounding a price onto the market's tick grid.

The grid is anchored at ``price_minimum``, because that is what the server
checks: ``(price - price_minimum) % price_tick``. This subtracted
``price % price_tick``, anchoring at zero -- right whenever the floor is a
multiple of the tick, and wrong for the rest in a way that produces a plausible
number rather than an error.

Same defect as B20's, in a different copy of the same rule. Mirrors 2212858.
"""

from __future__ import annotations

from fm.types import Market


def _market(minimum: int, maximum: int, tick: int) -> Market:
    return Market(id=11, marketplace_id=1, symbol="STK",
                  price_minimum=minimum, price_maximum=maximum, price_tick=tick)


def test_a_grid_anchored_at_a_multiple_of_the_tick():
    stock = _market(100, 200, 25)

    assert stock.price_round(137) == 125
    assert stock.price_round(125) == 125


def test_a_grid_anchored_away_from_zero():
    """110/135/160/185 are legal; the old code gave 125 for 137."""
    stock = _market(110, 199, 25)

    assert stock.price_round(137) == 135
    assert stock.price_round(199) == 185
    assert stock.price_round(110) == 110


def test_prices_outside_the_range_are_clamped():
    stock = _market(110, 199, 25)

    assert stock.price_round(5) == 110
    assert stock.price_round(10_000) == 185


def test_a_fixed_price_market_has_one_legal_price():
    """A tick of zero used to raise ZeroDivisionError."""
    fixed = _market(150, 150, 0)

    assert fixed.price_round(137) == 150
    assert fixed.price_round(9_999) == 150


def test_every_result_sits_on_the_grid():
    stock = _market(110, 199, 25)

    for price in range(0, 301):
        rounded = stock.price_round(price)
        assert (rounded - stock.price_minimum) % stock.price_tick == 0, (price, rounded)
        assert stock.price_minimum <= rounded <= stock.price_maximum
