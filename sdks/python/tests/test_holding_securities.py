"""Reading a holding's positions.

``get_security`` raised ValueError for a market the holder had no position in,
which is what every holding looks like before the first allocation -- ordinary,
not exceptional. And ``securities`` arrived in whatever order the server listed
them while ``units()`` sorted on the way out, so two reads of the same holding
disagreed about order and two holdings of the same positions compared unequal.

Mirrors e4d8151 in the Java SDK, where the same fix also collapsed two
disagreeing accessors into one.
"""

from __future__ import annotations

from fm.types import Holding, Security


def _security(market_id: int, units: int = 1) -> Security:
    return Security(market_id=market_id, units=units, available_units=units)


def test_positions_come_back_in_market_order():
    holding = Holding(securities=[_security(30), _security(10), _security(20)])

    assert [s.market_id for s in holding.securities] == [10, 20, 30]


def test_no_positions_reads_as_empty_rather_than_none():
    assert Holding(securities=None).securities == []


def test_two_holdings_of_the_same_positions_are_equal():
    assert Holding(securities=[_security(10), _security(20)]) == Holding(
        securities=[_security(20), _security(10)]
    )


def test_asking_for_a_position_the_holder_does_not_have_is_none_not_a_raise():
    holding = Holding(securities=[_security(10, 5)])

    assert holding.security(10).units == 5
    assert holding.security(99) is None


def test_units_follow_the_same_order():
    holding = Holding(securities=[_security(30, 3), _security(10, 1)])

    assert holding.units() == [1, 3]


def test_holding_takes_the_marketplace_alone():
    """It demanded a ``user_id`` it never used.

    The route is ``currentHolding`` -- the caller's by definition -- so the
    argument was accepted and discarded. Every call site had to invent a value,
    and one that passed somebody else's id got its own holding back with no
    indication of it. Java and TypeScript always took the marketplace alone;
    the parity check compared method names only, so it read as agreement.
    """
    import inspect

    from fm.client import Flexemarkets

    params = list(inspect.signature(Flexemarkets.holding).parameters)

    assert params == ["self", "marketplace_id"], params
