/**
 * Live-server smoke test for the TypeScript SDK. Mirrors the Java
 * FlexemarketsLiveServerTest. Catches catastrophic protocol regressions
 * (V0/V1 endpoint path drift, HATEOAS envelope shape, WS subscribe
 * destination, etc.) without needing a heavy test-server harness.
 *
 * Opt-in via two preconditions, both checked by `liveServerReady`:
 *   1. ~/.fm/credential and ~/.fm/endpoint both exist
 *   2. FM_LIVE_TESTS=1 env var is set
 *
 * Without those the suite self-skips so CI doesn't false-fail on a
 * missing server.
 *
 * Read-only: picks the endpoint's marketplace, opens a MarketView,
 * verifies the accessors don't throw, closes. No orders submitted; no
 * marketplaces created. Idempotent.
 */

import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import assert from "node:assert/strict";

import { Flexemarkets } from "../src/index.ts";

function liveServerReady(): boolean {
  if (process.env.FM_LIVE_TESTS !== "1") return false;
  const home = homedir();
  return existsSync(join(home, ".fm", "credential")) && existsSync(join(home, ".fm", "endpoint"));
}

const live = liveServerReady() ? test : test.skip;

live("connects to local server and lists marketplaces", async () => {
  const fm = await Flexemarkets.connect(null, null, "fm-sdk-live-test/connectAndList");
  try {
    const marketplaces = await fm.marketplaces();
    assert.ok(marketplaces.length > 0, "expected at least the endpoint's marketplace");
  } finally {
    fm.close();
  }
});

live("observes endpoint marketplace without throwing", async () => {
  const fm = await Flexemarkets.connect(null, null, "fm-sdk-live-test/observe");
  try {
    const marketplaceId = fm.endpointMarketplaceId;
    const view = await fm.observe(marketplaceId);
    try {
      assert.equal(view.marketplaceId, marketplaceId);
      assert.ok(view.markets.length > 0, "marketplace should have at least one market");
      for (const m of view.markets) {
        const book = view.orderBook(m.id);
        assert.ok(book !== null, `order book for market ${m.id} should be non-null`);
        // bestBuyPrice / bestSellPrice return -1 when empty; either
        // way they shouldn't throw, which is the real smoke-test
        // signal.
        assert.doesNotThrow(() => book!.bestBuyPrice());
        assert.doesNotThrow(() => book!.bestSellPrice());
      }
    } finally {
      view.close();
    }
  } finally {
    fm.close();
  }
});

live("shares view across multiple observe calls for same marketplace", async () => {
  const fm = await Flexemarkets.connect(null, null, "fm-sdk-live-test/sharedObserve");
  try {
    const marketplaceId = fm.endpointMarketplaceId;
    const a = await fm.observe(marketplaceId);
    const b = await fm.observe(marketplaceId);
    try {
      assert.equal(a.marketplaceId, b.marketplaceId);
      assert.deepEqual(a.markets, b.markets);
      // Closing one handle must NOT close the shared view — `b` should
      // still be usable.
      a.close();
      assert.doesNotThrow(() => b.markets);
    } finally {
      b.close();
    }
  } finally {
    fm.close();
  }
});

live("closing Flexemarkets force-closes any remaining shared views", async () => {
  const fm = await Flexemarkets.connect(null, null, "fm-sdk-live-test/forceClose");
  const marketplaceId = fm.endpointMarketplaceId;
  // Intentionally don't close the handle — fm.close() must sweep it
  // up so the WS subscription is released.
  await fm.observe(marketplaceId);
  assert.doesNotThrow(() => fm.close());
});
