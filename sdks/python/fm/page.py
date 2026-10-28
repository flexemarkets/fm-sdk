"""One page of a collection that grows without bound (fm-server 4.17)."""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Generic, TypeVar

from .exceptions import InvalidArgumentError

T = TypeVar("T")


@dataclass(frozen=True)
class Page(Generic[T]):
    """A page of a collection read with ``?limit=`` and ``?before=``.

    A page is the ``limit`` newest rows by id, in the order the collection is
    always answered in. :attr:`next` is what to pass as ``before`` for the page
    after it -- the older rows -- and is ``None`` on the last page. The server
    says so with ``Link: <...?before=...>; rel="next"``; a page without that
    header is the last, even when it is full::

        page = fm.sessions_page(marketplace_id, 100)
        while True:
            report(page.rows)
            if not page.has_next:
                break
            page = fm.sessions_page(marketplace_id, 100, page.next)
    """

    rows: list[T]
    next: int | None

    @property
    def has_next(self) -> bool:
        """Whether older rows remain."""
        return self.next is not None

    @staticmethod
    def of(rows: Sequence[T], id: Callable[[T], int], limit: int, before: int | None = None) -> Page[T]:
        """A page of a collection already read whole, cut as fm-server cuts one:
        the ``limit`` newest rows by id among those below ``before``, kept in
        the collection's order. For an implementation that reads only whole
        collections -- a test fake -- to page the way the server does.

        The server also caps a page at 5,000 rows; this does not.
        """
        if limit < 1:
            raise InvalidArgumentError(f"limit is 1 or more, not {limit}")
        older = [row for row in rows if before is None or id(row) < before]
        newest = sorted((id(row) for row in older), reverse=True)[:limit]
        if not newest:
            return Page(rows=[], next=None)
        oldest = newest[-1]
        page = [row for row in older if id(row) >= oldest]
        return Page(rows=page, next=oldest if len(older) > len(page) else None)
