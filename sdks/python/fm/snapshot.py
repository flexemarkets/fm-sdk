"""REST snapshot response bundle for MarketView Phase 2a seeding."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")

# Sentinel for "no seq header" — see Snapshot.as_of_seq docstring.
NO_SEQ = -1


@dataclass(frozen=True)
class Snapshot(Generic[T]):
    """Parsed body + the ``x-fm-as-of-seq`` header value the snapshot
    was read at. Returned by :class:`~fm.client.Flexemarkets` V1
    snapshot methods so :class:`~fm.market_view.MarketView` (and any
    other caller that mixes REST snapshots with WS deltas) can
    reconcile the two streams: apply deltas whose ``seq`` is greater
    than :attr:`as_of_seq` and skip those whose seq is less than or
    equal.

    :attr:`as_of_seq` is :data:`NO_SEQ` when the server didn't stamp
    the response (older fm-server). Callers that depend on the seq
    for correctness should treat ``NO_SEQ`` as "snapshot
    reconciliation unavailable" and either wholesale-replace state
    on every snapshot fetch or fall back to a periodic poll.
    """

    body: T
    as_of_seq: int
