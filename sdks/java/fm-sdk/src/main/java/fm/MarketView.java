package fm;

import fm.internal.DefaultMarketView;

import java.util.List;
import java.util.function.Consumer;


/**
 * Always-current view of a single marketplace, hiding the transport
 * details (WebSocket subscribe, snapshot+delta reconciliation,
 * sequence-gap recovery, reconnect) behind a small read-side surface.
 *
 * <p>Obtained via {@code Flexemarkets.observe(marketplaceId)}. The
 * returned instance owns a WebSocket subscription that stays live
 * until {@link #close()}. Per-{@code (marketplaceId, identity)}
 * sharing means two callers asking for the same marketplace under
 * the same client identity receive the same {@code MarketView}
 * instance — one WS connection, one materialized book, multiple
 * readers — and the underlying resources are released when the last
 * caller closes.
 *
 * <p><b>What it guarantees.</b> The books are seeded from a REST
 * snapshot and then kept current from the event stream, applying only
 * deltas newer than the snapshot they were seeded with. A gap in the
 * sequence is recovered by re-seeding rather than by carrying on with
 * a book that has a hole in it, and a dropped transport is
 * reconnected and re-seeded. Both are observable —
 * {@link #onGap(java.util.function.Consumer)} and
 * {@link #onReconnect(java.util.function.Consumer)} — so a caller who
 * wants to know that its view went stale can be told rather than
 * having to infer it.
 */
public interface MarketView extends AutoCloseable {

    /**
     * A view over any connection, maintained from its event stream.
     *
     * <p>The way a host that supplies its own {@link Flexemarkets} gets one.
     * A factory rather than a public constructor so the implementing class
     * stays an implementation detail: naming it in the published API would
     * commit to it for good, and callers only ever want "a view over this".
     */
    static MarketView over(Flexemarkets flexemarkets, long marketplaceId,
                           java.util.List<Market> markets) {
        return new DefaultMarketView(flexemarkets, marketplaceId, markets);
    }

    /**
     * The marketplace this view tracks.
     */
    long marketplaceId();

    /**
     * Markets in this marketplace, captured at observe-time.
     */
    List<Market> markets();

    /**
     * Always-current order book for {@code marketId}. Reads are atomic;
     * a caller never sees a half-applied delta.
     *
     * @return null if {@code marketId} isn't in this marketplace
     */
    OrderBook orderBook(long marketId);

    /**
     * Most-recent session update observed. Null until the first
     * {@code SESSION-UPDATE} frame lands.
     */
    Session session();

    /**
     * The caller's holding for this marketplace. Null until the first
     * {@code HOLDING-UPDATE} frame lands.
     */
    Holding holding();

    /**
     * Register a handler for session-state changes. Returns a
     * {@link Subscription} the caller closes to unregister.
     */
    Subscription onSessionChange(Consumer<Session> handler);

    /**
     * Register a handler that fires whenever the order book for
     * {@code marketId} changes. The handler receives the post-update
     * book; multiple deltas in one batch coalesce to one callback.
     */
    Subscription onOrderBookChange(long marketId, Consumer<OrderBook> handler);

    /**
     * Register a handler for the caller's holding changes.
     */
    Subscription onHoldingChange(Consumer<Holding> handler);

    /**
     * Register a handler that fires when {@link MarketView} detects a
     * gap in the ORDERS-UPDATE seq stream. Use this to wire your own
     * telemetry — by default the SDK logs the gap to stderr but
     * otherwise hides the recovery flow.
     */
    Subscription onGap(Consumer<GapEvent> handler);

    /**
     * Register a handler that fires after the SDK reacts to a
     * transport error — either when the reconnect + resnapshot has
     * completed successfully, or when the attempt has failed and the
     * view is left stale.
     */
    Subscription onReconnect(Consumer<ReconnectEvent> handler);

    /**
     * Submit a limit order on this marketplace.
     */
    Order submitLimit(long marketId, Side side, long units, long price);

    /**
     * Cancel a previously-submitted order.
     */
    Order submitCancel(long marketId, long originalId);

    /**
     * Release the WS subscription and any reader-side handles. After
     * close, accessors throw {@link IllegalStateException} and new
     * handler registrations are rejected. Idempotent.
     */
    @Override
    void close();
}
