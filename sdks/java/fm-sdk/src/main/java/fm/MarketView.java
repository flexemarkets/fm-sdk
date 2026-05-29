package fm;

import java.util.List;
import java.util.function.Consumer;

import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Order;
import fm.Types.Session;

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
 * <p><b>Phase 1 scope:</b> This interface defines the API surface.
 * The reference implementation is a skeleton that wraps the existing
 * {@code Flexemarkets.listen(...)} queue and dispatches events into
 * the existing {@link OrderBooks}/{@link MarketplaceTrades}
 * aggregators. <em>No reconciliation is wired yet</em> — REST-seed,
 * sequence-gap recovery, and per-identity sharing land in Phase 2.
 * Robots that don't need consistency-guaranteed startup state can
 * already use this; those that do should wait for Phase 2.
 */
public interface MarketView extends AutoCloseable {

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
    Order submitLimit(long marketId, String side, long units, long price);

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
