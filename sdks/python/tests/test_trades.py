"""What a Tape does that the behaviour fixtures do not drive.

The fixtures hold the three SDKs to the same trades for a match, a split and a
re-delivery. These are the rest: a capacity that cannot hold anything, the
pairs that look like a match and are not one, and the TapeIndex read of every
market's prices at once.
"""

from __future__ import annotations

import pytest

from fm.enums import OrderType
from fm.trades import Tape, TapeIndex
from fm.types import Market, Order

ALPHA = Market(id=1, marketplace_id=7, symbol="ALPHA")
BETA = Market(id=2, marketplace_id=7, symbol="BETA")


def _row(order_id: int, consumer: int | None, price: int, market: Market = ALPHA,
         type: OrderType = OrderType.LIMIT) -> Order:
    return Order(id=order_id, original=order_id, supplier=order_id, consumer=consumer,
                 type=type, side="SELL", units=1, price=price,
                 symbol=market.symbol, market_id=market.id)


def _match(maker: int, taker: int, price: int, market: Market = ALPHA) -> list[Order]:
    return [_row(maker, taker, price, market), _row(taker, maker, price, market)]


def test_a_tape_that_can_hold_no_trade_is_refused():
    with pytest.raises(ValueError, match="Capacity must be greater than zero"):
        Tape(ALPHA, capacity=0)


def test_a_tape_names_the_market_it_keeps():
    tape = Tape(ALPHA)

    assert tape.market is ALPHA
    assert tape.market_id == 1


def test_a_cancel_pair_is_not_a_trade():
    """A cancellation is two rows naming each other, like a match; the types
    differ. The LIMIT is consumed, and its consumer is the CANCEL."""
    tape = Tape(ALPHA)

    added = tape.update([_row(101, 105, 950), _row(105, 101, 950, type=OrderType.CANCEL)])

    assert added == []
    assert tape.size() == 0


def test_a_consumed_order_whose_taker_is_not_in_the_batch_is_not_a_trade():
    tape = Tape(ALPHA)

    assert tape.update([_row(101, 102, 950)]) == []
    assert tape.size() == 0


def test_the_index_reads_every_markets_prices_in_market_order():
    index = TapeIndex([BETA, ALPHA])

    index.update(_match(101, 102, 950) + _match(201, 202, 1200, BETA) + _match(103, 104, 975))

    assert index.most_recent_prices() == [[950, 975], [1200]]
