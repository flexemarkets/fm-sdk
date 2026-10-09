"""What a Book does that the behaviour fixtures do not drive.

The fixtures next door hold the three SDKs to the same answers on crosses,
splits, cancels and gaps. These are the Python-side edges around them: an
order for another market, a cancel that arrives without the order it
cancelled, the text a Book prints itself as, and the BookIndex reads that
answer one market of several.
"""

from __future__ import annotations

from fm.enums import OrderType
from fm.orderbook import Book, BookIndex
from fm.types import Market, Order

ALPHA = Market(id=1, marketplace_id=7, symbol="ALPHA")
BETA = Market(id=2, marketplace_id=7, symbol="BETA")


def _resting(order_id: int, side: str, units: int, price: int, market: Market = ALPHA) -> Order:
    return Order(id=order_id, original=order_id, supplier=order_id, consumer=None,
                 type=OrderType.LIMIT, side=side, units=units, price=price,
                 symbol=market.symbol, market_id=market.id)


def test_a_book_names_the_market_it_keeps():
    book = Book(ALPHA)

    assert book.market is ALPHA
    assert book.market_id == 1
    assert book.symbol == "ALPHA"


def test_an_order_for_another_market_leaves_the_book_alone():
    book = Book(ALPHA)

    book.update([_resting(1, "BUY", 5, 1000), _resting(2, "BUY", 9, 1500, BETA)])

    assert book.buy_levels() == [(1000, 5)]


def test_a_cancel_without_the_order_it_cancelled_takes_its_units_off():
    """fm-server sends a cancel with the limit it consumed; Book removes the
    units once, through the limit. A CANCEL arriving alone still has to take
    them off, or the level keeps units nobody is offering."""
    book = Book(ALPHA)
    book.update([_resting(101, "SELL", 5, 950), _resting(102, "SELL", 3, 950)])

    book.update([Order(id=110, original=101, supplier=101, consumer=101,
                       type=OrderType.CANCEL, side="SELL", units=5, price=950,
                       symbol="ALPHA", market_id=1)])

    assert book.sell_levels() == [(950, 3)]


def test_an_empty_book_prints_as_empty_sides_and_no_spread():
    assert repr(Book(ALPHA)) == "\n".join([
        "-----BOOK-----",
        "      --      ",
        "--------------",
        "spread        ",
        "--------------",
        "      --      ",
        "--------------",
    ])


def test_a_book_prints_offers_above_bids_with_the_spread_between():
    book = Book(ALPHA)
    book.update([_resting(1, "BUY", 5, 1000), _resting(2, "SELL", 3, 1250),
                 _resting(3, "SELL", 12, 1300)])

    assert repr(book) == "\n".join([
        "-----BOOK-----",
        "S 12\t$13.00",
        "S  3\t$12.50",
        "--------------",
        "spread  $ 2.50",
        "--------------",
        "B  5\t$10.00",
        "--------------",
    ])


def test_a_one_sided_book_prints_no_spread():
    book = Book(ALPHA)
    book.update([_resting(1, "BUY", 5, 1000)])

    lines = repr(book).split("\n")

    assert lines[1] == "      --      "
    assert lines[3] == "spread        "
    assert lines[5] == "B  5\t$10.00"


def test_the_index_answers_for_the_market_asked_about():
    index = BookIndex([ALPHA, BETA])
    index.update([_resting(1, "BUY", 5, 1000), _resting(2, "SELL", 4, 2000, BETA)])

    assert index.has_value(ALPHA.id, "BUY") is True
    assert index.has_value(ALPHA.id, "SELL") is False
    assert index.has_value(BETA.id, "SELL") is True
    assert index.best_price(ALPHA.id, "BUY") == 1000
    assert index.best_price(BETA.id, "SELL") == 2000
    assert index.best_price(BETA.id, "BUY") == -1
