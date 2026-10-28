"""Only a call market shows an indicative price.

A continuous market stores a book setting too, which means nothing for it,
and reading the setting alone would promise an indicative price the server
refuses to give.
"""

from fm.client import _parse_market
from fm.types import Market

BASE = {"id": 12, "marketplaceId": 3050, "symbol": "BAT"}


def test_only_an_indicative_call_market_shows_its_indicative_price():
    assert _parse_market({**BASE, "mechanism": "CALL", "callBook": "INDICATIVE"}).shows_indicative()
    assert not _parse_market({**BASE, "mechanism": "CALL", "callBook": "SEALED"}).shows_indicative()
    assert not _parse_market({**BASE, "mechanism": "CONTINUOUS", "callBook": "INDICATIVE"}).shows_indicative(), \
        "the book setting means nothing without calls"


def test_a_call_market_that_does_not_say_is_sealed():
    market = _parse_market({**BASE, "mechanism": "CALL"})

    assert market.call_book == Market.CALL_BOOK_SEALED
    assert not market.shows_indicative()
