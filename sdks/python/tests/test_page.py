"""Page.of cuts a page as fm-server's Paging.page does.

The client's pages are the route fixtures' to check; this is the cut a fake that
reads only whole collections pages with.
"""

import pytest

from fm import Page
from fm.exceptions import InvalidArgumentError

# A collection answered newest first, so its order is not its id order.
NEWEST_FIRST = [50, 40, 30, 20, 10]


def _ident(row: int) -> int:
    return row


def test_a_page_is_the_newest_by_id_in_the_collections_own_order() -> None:
    page = Page.of(NEWEST_FIRST, _ident, 2)

    assert page.rows == [50, 40]
    assert page.next == 40
    assert page.has_next


def test_before_reads_the_older_rows() -> None:
    page = Page.of(NEWEST_FIRST, _ident, 2, 40)

    assert page.rows == [30, 20]
    assert page.next == 20


def test_a_full_last_page_has_no_next() -> None:
    page = Page.of(NEWEST_FIRST, _ident, 2, 30)

    assert page.rows == [20, 10]
    assert page.next is None
    assert not page.has_next


def test_a_page_with_nothing_older_is_empty_and_last() -> None:
    page = Page.of(NEWEST_FIRST, _ident, 2, 10)

    assert page.rows == []
    assert page.next is None


def test_a_limit_below_one_is_refused_as_the_server_refuses_it() -> None:
    with pytest.raises(InvalidArgumentError):
        Page.of(NEWEST_FIRST, _ident, 0)
