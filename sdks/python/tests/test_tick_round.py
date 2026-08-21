"""Rounding a price onto the market's tick grid.

The grid is anchored at ``price_minimum``, because that is what the server
checks: ``(price - price_minimum) % price_tick``. This subtracted
``price % price_tick``, anchoring at zero -- right whenever the floor is a
multiple of the tick, and wrong for the rest in a way that produces a plausible
number rather than an error.

Same defect as B20's, in a different copy of the same rule. Mirrors 2212858.
"""

from __future__ import annotations

import pytest

from fm.types import Market, tick_round


def _legacy_round(value: int, minimum: int, maximum: int, tick: int) -> int:
    """The arithmetic this replaced, kept so the defect is demonstrable."""
    return min(max(value - value % tick, minimum), maximum)


def _server_would_accept(value: int, minimum: int, maximum: int, tick: int) -> bool:
    """The server's own rule, from OrderDtoConverter -- the oracle.

    A value must lie within its bounds and, unless the dimension is fixed, sit
    on a tick measured from the minimum.
    """
    if value < minimum or value > maximum:
        return False
    return tick <= 0 or (value - minimum) % tick == 0


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


def test_units_round_onto_their_own_grid():
    """The server refuses an off-tick size just as it does an off-tick price."""
    odd = Market(id=11, marketplace_id=1, symbol="STK",
                 price_minimum=100, price_maximum=200, price_tick=25,
                 unit_minimum=3, unit_maximum=97, unit_tick=5)

    assert odd.unit_round(20) == 18, "legal sizes are 3, 8, 13, 18, 23"
    assert odd.unit_round(1) == 3
    assert odd.unit_round(1_000) == 93, "the highest legal tick, not 97"


def test_both_dimensions_share_the_grid():
    square = Market(id=11, marketplace_id=1, symbol="STK",
                    price_minimum=110, price_maximum=199, price_tick=25,
                    unit_minimum=110, unit_maximum=199, unit_tick=25)

    assert square.unit_round(137) == square.price_round(137)


def test_every_result_sits_on_the_grid():
    stock = _market(110, 199, 25)

    for price in range(0, 301):
        rounded = stock.price_round(price)
        assert (rounded - stock.price_minimum) % stock.price_tick == 0, (price, rounded)
        assert stock.price_minimum <= rounded <= stock.price_maximum


# --- the defects, demonstrated ----------------------------------------------

def test_the_old_arithmetic_produced_prices_the_server_refuses():
    minimum, maximum, tick = 110, 199, 25

    legacy = _legacy_round(137, minimum, maximum, tick)
    assert legacy == 125
    assert not _server_would_accept(legacy, minimum, maximum, tick), \
        "125 is inside the bounds and off the tick"

    fixed = tick_round(137, minimum, maximum, tick)
    assert fixed == 135
    assert _server_would_accept(fixed, minimum, maximum, tick)


def test_clamping_to_the_maximum_would_also_be_refused():
    minimum, maximum, tick = 110, 199, 25

    assert not _server_would_accept(maximum, minimum, maximum, tick), \
        "199 is the ceiling and is not itself a legal price"

    fixed = tick_round(210, minimum, maximum, tick)
    assert fixed == 185
    assert _server_would_accept(fixed, minimum, maximum, tick)


def test_the_old_arithmetic_divided_by_zero_on_a_fixed_dimension():
    with pytest.raises(ZeroDivisionError):
        _legacy_round(137, 150, 150, 0)

    assert tick_round(137, 150, 150, 0) == 150


def test_every_rounded_value_would_be_accepted_by_the_server():
    """Both dimensions, judged by the server's rule rather than by example."""
    grids = [
        (110, 199, 25),   # range not a whole number of ticks
        (100, 200, 25),   # floor on the tick
        (3, 97, 5),       # the unit dimension
        (150, 150, 0),    # fixed
        (0, 1000, 1),     # tick of one
    ]

    for minimum, maximum, tick in grids:
        for value in range(-50, maximum + 51):
            rounded = tick_round(value, minimum, maximum, tick)
            assert _server_would_accept(rounded, minimum, maximum, tick), \
                f"grid [{minimum},{maximum}]/{tick} rounded {value} to {rounded}"
