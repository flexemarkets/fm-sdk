"""Live-server smoke test for the Python SDK.

Mirrors the Java FlexemarketsLiveServerTest. Catches catastrophic
protocol regressions (V0/V1 endpoint path drift, HATEOAS envelope
shape, WS subscribe destination, etc.) without needing a heavy
test-server harness.

Opt-in via two preconditions, both checked by ``live_server_ready``:

1. ``~/.fm/credential`` and ``~/.fm/endpoint`` both exist
2. ``FM_LIVE_TESTS=1`` env var is set

Without those the suite self-skips so CI doesn't false-fail on a
missing server.

Read-only: picks the endpoint's marketplace, opens a MarketView,
verifies the accessors don't throw, closes. No orders submitted; no
marketplaces created. Idempotent.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from fm import Flexemarkets


def _live_server_ready() -> bool:
    if os.environ.get("FM_LIVE_TESTS") != "1":
        return False
    home = Path.home()
    return (home / ".fm" / "credential").exists() and (home / ".fm" / "endpoint").exists()


pytestmark = pytest.mark.skipif(
    not _live_server_ready(),
    reason="FM_LIVE_TESTS=1 not set or ~/.fm config missing",
)


def test_connects_to_local_server_and_lists_marketplaces():
    fm = Flexemarkets.connect(client_description="fm-sdk-live-test/connectAndList")
    try:
        marketplaces = fm.marketplaces()
        assert len(marketplaces) > 0, "expected at least the endpoint's marketplace"
    finally:
        fm.close()


def test_observes_endpoint_marketplace_without_throwing():
    fm = Flexemarkets.connect(client_description="fm-sdk-live-test/observe")
    try:
        marketplace_id = fm.endpoint_marketplace_id
        view = fm.observe(marketplace_id)
        try:
            assert view.marketplace_id == marketplace_id
            assert len(view.markets) > 0, "marketplace should have at least one market"
            for m in view.markets:
                book = view.order_book(m.id)
                assert book is not None, f"order book for market {m.id} should be non-null"
                # best_buy_price / best_sell_price return -1 when empty;
                # either way they shouldn't throw, which is the real
                # smoke-test signal.
                book.best_buy_price()
                book.best_sell_price()
        finally:
            view.close()
    finally:
        fm.close()


def test_shares_view_across_multiple_observe_calls_for_same_marketplace():
    fm = Flexemarkets.connect(client_description="fm-sdk-live-test/sharedObserve")
    try:
        marketplace_id = fm.endpoint_marketplace_id
        a = fm.observe(marketplace_id)
        b = fm.observe(marketplace_id)
        try:
            assert a.marketplace_id == b.marketplace_id
            assert a.markets == b.markets
            # Closing one handle must NOT close the shared view — `b`
            # should still be usable.
            a.close()
            _ = b.markets  # should not raise
        finally:
            b.close()
    finally:
        fm.close()


def test_closing_flexemarkets_force_closes_remaining_shared():
    fm = Flexemarkets.connect(client_description="fm-sdk-live-test/forceClose")
    marketplace_id = fm.endpoint_marketplace_id
    # Intentionally don't close the handle — fm.close() must sweep it
    # up so the WS subscription is released.
    fm.observe(marketplace_id)
    fm.close()  # should not raise
