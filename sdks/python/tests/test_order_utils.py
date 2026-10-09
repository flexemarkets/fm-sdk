"""
The relational order helpers, held to what Java's OrdersTest holds.

These three arrived in Python and TypeScript after existing in Java alone.
Java tests them; the other two did not test order utilities at all, so the
behaviour was pinned in one language and free to drift in two.
"""

import pytest

from fm.enums import OrderSide, OrderType
from fm.order_utils import (find_order, is_consumed_or_split, is_resting, is_submit,
                            is_supplier, is_symbol, limit)
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


def row(order_id: int, original: int, supplier: int, consumer: int | None,
        type: OrderType = OrderType.LIMIT) -> Order:
    """One order row as the server sends it: its id and the three it refers to."""
    return Order(id=order_id, original=original, supplier=supplier, consumer=consumer,
                 type=type, side=OrderSide.SELL, symbol="ALPHA", units=1, price=100)


class TestIsSymbol:
    def test_no_symbol_matches_every_order(self):
        assert is_symbol(None, Order(symbol="ALPHA")) is True
        assert is_symbol(None, Order(symbol=None)) is True

    def test_matches_whatever_the_case(self):
        assert is_symbol("alpha", Order(symbol="ALPHA")) is True
        assert is_symbol("ALPHA", Order(symbol="BETA")) is False

    def test_an_order_without_a_symbol_is_in_no_market(self):
        assert is_symbol("ALPHA", Order(symbol=None)) is False


class TestIsSubmit:
    def test_a_fresh_order_is_its_own_original_and_supplier(self):
        assert is_submit(row(5, 5, 5, None)) is True

    def test_a_fragment_is_not_a_submission(self):
        assert is_submit(row(9, 5, 5, None)) is False
        assert is_submit(row(9, 9, 5, None)) is False

    def test_a_cancel_is_a_submission_whatever_its_ids(self):
        assert is_submit(row(9, 5, 5, None, OrderType.CANCEL)) is True


class TestFindOrder:
    def test_finds_by_id(self):
        orders = [row(1, 1, 1, None), row(2, 2, 2, None)]
        assert find_order(orders, 2) is orders[1]

    def test_no_id_and_an_absent_id_find_nothing(self):
        orders = [row(1, 1, 1, None)]
        assert find_order(orders, None) is None
        assert find_order(orders, 3) is None


class TestIsResting:
    """Which side of a match was already on the book.

    The lineages are fm-server's, as Order Data Format lays them out: a split
    leaves a marker (consumer 0) carrying the original size and children keyed
    to it by original and supplier. What a batch does not hold came before it.
    """

    def test_an_available_order_is_resting(self):
        assert is_resting([], row(1, 1, 1, None)) is True

    def test_a_cancel_is_never_resting_and_what_it_cancelled_was(self):
        cancelled, cancel = row(101, 101, 101, 105), row(105, 101, 101, 101, OrderType.CANCEL)
        orders = [cancelled, cancel]

        assert is_resting(orders, cancel) is False
        assert is_resting(orders, cancelled) is True

    def test_in_a_full_match_the_earlier_order_rested(self):
        maker, taker = row(101, 101, 101, 102), row(102, 102, 102, 101)
        orders = [maker, taker]

        assert is_resting(orders, maker) is True
        assert is_resting(orders, taker) is False

    def test_a_remainder_taken_later_rested_and_its_taker_did_not(self):
        """201 was split in an earlier batch and 203 is what it left. Neither
        201 nor the batch that split it is here; only the remainder and the
        order that takes it."""
        remainder, taker = row(203, 201, 201, 205), row(205, 205, 205, 203)
        orders = [remainder, taker]

        assert is_resting(orders, remainder) is True
        assert is_resting(orders, taker) is False

    def test_a_resting_order_split_by_a_smaller_taker(self):
        """15 offered at 201; a bid for 10 (204) takes 202 and leaves 203."""
        marker = row(201, 201, 201, 0)
        matched, remainder = row(202, 201, 201, 204), row(203, 201, 201, None)
        taker = row(204, 204, 204, 202)
        orders = [marker, matched, remainder, taker]

        assert is_resting(orders, marker) is True
        assert is_resting(orders, matched) is True
        assert is_resting(orders, taker) is False

    def test_a_taker_split_by_a_smaller_resting_order(self):
        """5 offered at 301; a bid for 10 (302) takes it with 303 and rests 304.
        The split is the taker's, so its marker is not the resting side."""
        maker = row(301, 301, 301, 303)
        marker = row(302, 302, 302, 0)
        matched, remainder = row(303, 302, 302, 301), row(304, 302, 302, None)
        orders = [maker, marker, matched, remainder]

        assert is_resting(orders, maker) is True
        assert is_resting(orders, marker) is False
        assert is_resting(orders, matched) is False

    def test_a_remainder_split_again(self):
        """203 is what an earlier split of 201 left; a bid (208) now takes part of
        it, so 203 splits in turn into 206, matched, and 207, resting."""
        marker = row(203, 201, 201, 0)
        matched, remainder = row(206, 201, 203, 208), row(207, 201, 203, None)
        taker = row(208, 208, 208, 206)
        orders = [marker, matched, remainder, taker]

        assert is_resting(orders, matched) is True
        assert is_resting(orders, taker) is False

    def test_a_remainder_whose_taker_is_absent_rested(self):
        """203 was cut from 201 in an earlier batch; neither 201 nor 205, which
        took it, is here. What the batch does not hold came before it."""
        assert is_resting([row(203, 201, 201, 205)], row(203, 201, 201, 205)) is True

    def test_a_split_marker_whose_children_are_absent_is_not_resting(self):
        """The other row is a different lineage, not a child of the marker."""
        marker, other = row(201, 201, 201, 0), row(150, 140, 140, 160)

        assert is_resting([other, marker], marker) is False

    def test_a_split_marker_whose_childs_supplier_is_absent_rested(self):
        """206 was cut from 203, which is not in the batch: it came before."""
        marker = row(201, 201, 201, 0)
        child, taker = row(206, 201, 203, 208), row(208, 208, 208, 206)
        orders = [marker, child, taker]

        assert is_resting(orders, marker) is True

    def test_two_lineages_neither_of_whose_originals_is_in_the_batch(self):
        """No server sends this -- a taker's original arrives with it -- but the
        function takes any list. With nothing to compare, each side's lineage
        reads as the older and the answer is True for both, not a failure;
        Java's Orders.isResting throws NullPointerException here."""
        left_marker, left = row(203, 201, 201, 0), row(206, 201, 203, 208)
        right_marker, right = row(209, 210, 210, 0), row(208, 210, 209, 206)
        orders = [left_marker, left, right_marker, right]

        assert is_resting(orders, left) is True
        assert is_resting(orders, right) is True
