"""
The relational order helpers, held to what Java's OrdersTest holds.

These three arrived in Python and TypeScript after existing in Java alone.
Java tests them; the other two did not test order utilities at all, so the
behaviour was pinned in one language and free to drift in two.
"""

import pytest

from fm.enums import OrderSide, OrderType
from fm.order_utils import is_consumed_or_split, is_supplier, limit
from fm.types import Market, Order


def market() -> Market:
    # Distinct ids on purpose: marketplace_id and market_id are both ints and
    # adjacent in meaning, so equal values would hide a transposition.
    return Market(id=77, marketplace_id=42, symbol="ALPHA")


def order(order_id: int, supplier: int) -> Order:
    return Order(id=order_id, supplier=supplier)


class TestLimit:
    def test_puts_every_value_in_the_field_it_belongs_in(self):
        o = limit(market(), OrderSide.BUY, 3, 250)

        assert o.marketplace_id == 42
        assert o.market_id == 77
        assert o.symbol == "ALPHA"
        assert o.side is OrderSide.BUY
        assert o.units == 3
        assert o.price == 250
        assert o.type is OrderType.LIMIT

    def test_does_not_confuse_market_id_with_marketplace_id(self):
        o = limit(market(), OrderSide.SELL, 1, 10)

        assert o.market_id != o.marketplace_id
        assert o.market_id == 77
        assert o.marketplace_id == 42

    def test_does_not_confuse_units_with_price(self):
        o = limit(market(), OrderSide.BUY, 2, 900)

        assert o.units == 2
        assert o.price == 900

    def test_leaves_server_assigned_fields_empty(self):
        """A new order has no identity or lineage until the platform gives it one."""
        o = limit(market(), OrderSide.BUY, 1, 10)

        assert o.id == 0
        assert o.original == 0
        assert o.supplier == 0
        assert o.consumer is None

    @pytest.mark.parametrize("side", [OrderSide.BUY, OrderSide.SELL])
    def test_carries_the_side_it_is_given(self, side):
        assert limit(market(), side, 1, 10).side is side


class TestIsConsumedOrSplit:
    def test_is_true_once_there_is_a_consumer(self):
        assert is_consumed_or_split(Order(consumer=None)) is False
        assert is_consumed_or_split(Order(consumer=9)) is True

    def test_tolerates_a_none_order(self):
        assert is_consumed_or_split(None) is False


class TestIsSupplier:
    def test_matches_an_order_against_the_one_that_supplied_it(self):
        maker, taker = order(100, 0), order(200, 100)

        assert is_supplier(maker, taker) is True
        assert is_supplier(taker, maker) is False

    def test_tolerates_nones(self):
        assert is_supplier(None, order(1, 0)) is False
        assert is_supplier(order(1, 0), None) is False
        assert is_supplier(None, None) is False
