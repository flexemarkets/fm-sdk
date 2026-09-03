package fm;

import fm.event.GapEvent;
import fm.event.ReconnectEvent;
import fm.model.Holding;
import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.Session;
import fm.model.Trade;
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
     *
     * @param flexemarkets the connected client the view reads and writes through
     * @param marketplaceId the marketplace to track
     * @param markets     the marketplace's markets, as read once at observe-time
     * @return a live view, already seeded and receiving updates
     */
    static MarketView over(Flexemarkets flexemarkets, long marketplaceId,
                           java.util.List<Market> markets) {
        return new DefaultMarketView(flexemarkets, marketplaceId, markets);
    }

    /**
     * The marketplace this view tracks.
     *
     * @return the marketplace's id
     */
    long marketplaceId();

    /**
     * Markets in this marketplace, captured at observe-time.
     *
     * @return the markets, as captured when the view was opened
     */
    List<Market> markets();

    /**
     * Always-current order book for {@code marketId}. Reads are atomic;
     * a caller never sees a half-applied delta.
     *
     * @param marketId the market to read
     * @return that market's book, current as of this call, or null when
     *         {@code marketId} is not in this marketplace
     */
    Book orderBook(long marketId);

    /**
     * Always-current trade tape for {@code marketId}, most recent last.
     *
     * <p>Maintained alongside {@link #orderBook}: seeded from the server's
     * recent trades when the view opens, then kept current from the same delta
     * stream. Reads are atomic, on the same terms as the book.
     *
     * <p>A trade is not a distinct thing on the wire -- the exchange expresses
     * one as a pair of orders referring to each other -- so the tape holds
     * {@link Trade}, which keeps both: the resting order that carries the price
     * the trade happened at, and the aggressor that came in and took it.
     *
     * @param marketId the market to read
     * @return that market's tape, current as of this call, or null when
     *         {@code marketId} is not in this marketplace
     */
    Tape trades(long marketId);

    /**
     * Most-recent session update observed. Null until the first
     * {@code SESSION-UPDATE} frame lands.
     *
     * @return the marketplace's session, or null before the first arrives
     */
    Session session();

    /**
     * The caller's holding for this marketplace. Null until the first
     * {@code HOLDING-UPDATE} frame lands.
     *
     * @return the caller's holding, or null before the first arrives
     */
    Holding holding();

    /**
     * Register a handler for session-state changes. Returns a
     * {@link Subscription} the caller closes to unregister.
     *
     * @param handler called with each new session state
     * @return a handle that unsubscribes the handler
     */
    Subscription onSessionChange(Consumer<Session> handler);

    /**
     * Register a handler that fires whenever the order book for
     * {@code marketId} changes. The handler receives the post-update
     * book; multiple deltas in one batch coalesce to one callback.
     *
     * @param marketId the market to watch
     * @param handler  called with that market's book after each change
     * @return a handle that unsubscribes the handler
     */
    Subscription onOrderBookChange(long marketId, Consumer<Book> handler);

    /**
     * Register a handler that fires for each trade on {@code marketId}.
     *
     * <p>The missing member of the family {@link #onOrderBookChange} and
     * {@link #onSessionChange} belong to. Without it, "tell me when a trade
     * happens" is asked as "tell me when the book changed, then let me look" --
     * which answers a different question, since a book changes on every resting
     * order and most changes are not trades.
     *
     * <p>Fires <b>once per trade</b>, oldest first, rather than coalescing a
     * batch the way {@link #onOrderBookChange} does. A trade is a discrete
     * event with its own price and counterparties; collapsing two into one
     * callback would lose one of them.
     *
     * <p>Live deltas only. A gap or a reconnect re-seeds the tape from the
     * server's snapshot, and those trades do not fire here -- they are not new,
     * they are what was missed. {@link #onGap} and {@link #onReconnect} are how
     * a caller learns that happened.
     *
     * @param marketId the market to watch
     * @param handler  called with each trade as it lands
     * @return a handle that unsubscribes the handler
     */
    Subscription onTrade(long marketId, Consumer<Trade> handler);

    /**
     * Register a handler for the caller's holding changes.
     *
     * @param handler called with the holding after each change
     * @return a handle that unsubscribes the handler
     */
    Subscription onHoldingChange(Consumer<Holding> handler);

    /**
     * Register a handler that fires when {@link MarketView} detects a
     * gap in the ORDERS-UPDATE seq stream. Use this to wire your own
     * telemetry — by default the SDK logs the gap to stderr but
     * otherwise hides the recovery flow.
     *
     * @param handler called when a sequence gap is detected
     * @return a handle that unsubscribes the handler
     */
    Subscription onGap(Consumer<GapEvent> handler);

    /**
     * Register a handler that fires after the SDK reacts to a
     * transport error — either when the reconnect + resnapshot has
     * completed successfully, or when the attempt has failed and the
     * view is left stale.
     *
     * @param handler called after the stream is restored and the view reseeded
     * @return a handle that unsubscribes the handler
     */
    Subscription onReconnect(Consumer<ReconnectEvent> handler);

    /**
     * Submit a limit order on this marketplace.
     *
     * @param marketId the market to trade in
     * @param side     buy or sell
     * @param units    the size, which must sit on the market's unit grid
     * @param price    the limit, which must sit on the market's price grid
     * @return the order as accepted, with its server-assigned id
     */
    Order submitLimit(long marketId, OrderSide side, long units, long price);

    /**
     * Cancel a previously-submitted order.
     *
     * @param marketId   the market the original order is in
     * @param originalId the id of the order to cancel
     * @return the cancel order as accepted
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
