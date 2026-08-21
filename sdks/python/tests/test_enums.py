"""Side and OrderType in Python, where StrEnum makes the type nearly free.

The Java SDK's enums are a hard break: a caller passing "BUY" stops compiling.
Python's StrEnum members *are* strings, so they serialise with no encoder,
compare equal to the wire spelling, and a caller who was passing "BUY" keeps
working. The type is gained without the break.

That property is easy to lose. Comparing with ``is`` rather than ``==`` defeats
it -- ``"BUY" is Side.BUY`` is False -- which is why the SDK coerces at the
boundary with ``Side.of`` instead of assuming an enum arrived.
"""

from __future__ import annotations

import json

from fm.enums import OrderType, Side
from fm.order_utils import contra, is_buy, is_cancel, is_limit, is_sell
from fm.types import Order


def test_a_member_is_a_string():
    assert Side.BUY == "BUY"
    assert isinstance(Side.BUY, str)
    assert json.dumps({"side": Side.BUY}) == '{"side": "BUY"}'


def test_a_side_is_read_whatever_its_casing():
    assert Side.of("BUY") is Side.BUY
    assert Side.of("buy") is Side.BUY
    assert Side.of(" Sell ") is Side.SELL


def test_an_unknown_value_is_none_rather_than_a_raise():
    assert Side.of("BYU") is None
    assert Side.of(None) is None
    assert OrderType.of("MARKET") is None, "the server has sent this"


def test_contra_pairs_the_two_sides():
    assert Side.BUY.contra() is Side.SELL
    assert contra("sell") is Side.BUY, "a plain string still works"


def test_the_helpers_take_either_an_enum_or_a_string():
    assert is_buy(Side.BUY) and is_buy("buy")
    assert is_sell("SELL") and not is_sell(Side.BUY)

    limit = Order(id=1, original=1, supplier=1, type="LIMIT", side="BUY")
    assert is_limit(limit) and not is_cancel(limit)

    cancel = Order(id=2, original=1, supplier=1, type=OrderType.CANCEL, side=None)
    assert is_cancel(cancel) and not is_limit(cancel)
