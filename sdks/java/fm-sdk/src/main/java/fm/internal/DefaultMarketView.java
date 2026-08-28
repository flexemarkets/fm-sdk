package fm.internal;

import fm.Flexemarkets;
import fm.FrameUnreadable;
import fm.GapEvent;
import fm.Holding;
import fm.Market;
import fm.MarketView;
import fm.Marketplace;
import fm.MarketplaceTrades;
import fm.Order;
import fm.MarketBook;
import fm.MarketplaceBooks;
import fm.OrdersUpdate;
import fm.ReconnectEvent;
import fm.Reconnected;
import fm.Session;
import fm.Side;
import fm.Snapshot;
import fm.StreamDropped;
import fm.Subscription;
import fm.Trades;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


/**
 * Skeleton {@link MarketView} that wraps an existing
 * {@link Flexemarkets} client. Phase 1 of the SDK roadmap (see
 * {@code project_fm_sdk_canonical_client}).
 *
 * <p><b>What this does today:</b>
 * <ul>
 *   <li>Captures the marketplace's markets at observe-time.</li>
 *   <li>Calls {@code flexemarkets.listen(marketplaceId, queue)} to
 *       drive a WS subscription, draining the queue in a background
 *       thread.</li>
 *   <li>Dispatches WS events into the existing {@link MarketplaceBooks}
 *       and {@link MarketplaceTrades} aggregators, plus
 *       {@link #session} / {@link #holding} fields.</li>
 *   <li>Fires registered handlers when state changes.</li>
 * </ul>
 *
 * <p><b>What's intentionally missing (Phase 2 lands these):</b>
 * <ul>
 *   <li>REST snapshot seeding ({@code GET /v1/orders/active},
 *       {@code GET /v1/orders/recent-trades}). Today the book starts
 *       empty and accretes from incoming deltas only.</li>
 *   <li>Sequence-gap recovery (the {@code seq} header on
 *       ORDERS-UPDATE frames is ignored today).</li>
 *   <li>Per-{@code (marketplaceId, identity)} sharing — every
 *       {@code observe(...)} call returns a fresh view with its own
 *       WS connection. Sharing is a Phase 2 unlock.</li>
 *   <li>Reconnect handling — a {@link StreamDropped} from the
 *       queue currently terminates the dispatcher with no automatic
 *       reconnect.</li>
 * </ul>
 *
 * Robots that don't depend on consistency-guaranteed initial state
 * (today's fm-maker / fm-taker) can already use this. Robots that do
 * (studies, MVO variants) should wait for Phase 2.
 */
public class DefaultMarketView implements MarketView {

    private final Flexemarkets flexemarkets;
    private final long marketplaceId;
    private final List<Market> markets;

    private final MarketplaceBooks orderBooks;
    private final MarketplaceTrades trades;
    private final AtomicReference<Session> session = new AtomicReference<>();
    private final AtomicReference<Holding> holding = new AtomicReference<>();

    // CopyOnWriteArrayList because handler arrays are read once per
    // dispatch (hot path) and mutated rarely (register / unregister
    // happens on robot startup / shutdown).
    private final List<Consumer<Session>>                  sessionHandlers   = new CopyOnWriteArrayList<>();
    private final List<Consumer<Holding>>                  holdingHandlers   = new CopyOnWriteArrayList<>();
    private final List<OrderBookHandler>                   bookHandlers      = new CopyOnWriteArrayList<>();
    private final List<TradeHandler>                       tradeHandlers     = new CopyOnWriteArrayList<>();
    private final List<Consumer<GapEvent>>                 gapHandlers       = new CopyOnWriteArrayList<>();
    private final List<Consumer<ReconnectEvent>>           reconnectHandlers = new CopyOnWriteArrayList<>();

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(10_000);
    private final Subscription events;
    private final Thread dispatcher;
    private volatile boolean closed;

    /**
     * Highest ORDERS-UPDATE seq this view has applied so far. Deltas
     * with {@code seq <= lastAppliedSeq} are skipped — they've already
     * been folded into the local state (either via the initial REST
     * snapshot or via a previously-applied delta). Initial value comes
     * from the snapshot's {@code asOfSeq}; {@link Snapshot#NO_SEQ}
     * disables filtering (older fm-server).
     */
    private long lastAppliedSeq;

    /**
     * A view over one marketplace's markets, seeded but not yet observing.
     *
     * @param flexemarkets  the connection to read and stream through
     * @param marketplaceId the marketplace to follow
     * @param markets       its markets, which fix the books and tapes kept
     */
    public DefaultMarketView(Flexemarkets flexemarkets, long marketplaceId, List<Market> markets) {
        this.flexemarkets = flexemarkets;
        this.marketplaceId = marketplaceId;
        this.markets = List.copyOf(markets);
        this.orderBooks = new MarketplaceBooks(this.markets);
        // 100 matches the default per-market Trades capacity — see
        // Trades(Market) ctor. Plumb through to observe() later if a
        // caller needs deeper trade scrollback.
        this.trades = new MarketplaceTrades(this.markets, 100);

        // Subscribe WS first so deltas start landing in the queue,
        // then fetch the REST snapshot, apply it, and only THEN start
        // the dispatcher. Any deltas that arrive between subscribe
        // and snapshot apply sit in the queue and get filtered by
        // seq when the dispatcher runs.
        //
        // Phase 2d: own the Events instance directly rather than
        // calling flexemarkets.listen() — that would clobber the
        // singleton events field and prevent multiple shared views
        // on different marketplaces from coexisting in one
        // Flexemarkets.
        this.events = flexemarkets.subscribe(marketplaceId, queue);
        _seedFromSnapshot();
        this.dispatcher = Thread.startVirtualThread(this::_drain);
    }

    /**
     * Phase 2a snapshot seeding. Fetches the V1 active-orders and
     * recent-trades snapshots, applies them to the local aggregators,
     * and records the {@code asOfSeq} so subsequent WS deltas can be
     * filtered to avoid double-applying anything the snapshot already
     * reflects.
     *
     * <p>Known race window: a delta whose order persisted between the
     * server's seq capture and its order read can appear both in the
     * snapshot and in a delta with {@code seq > asOfSeq}, leading to
     * a double-apply. Same caveat as the existing V0 WS snapshot path
     * — fix lands in Phase 2b (gap recovery with ID-based dedup) or
     * via a server-side publish lock.
     */
    private void _seedFromSnapshot() {
        Snapshot<List<Order>> orders = flexemarkets.activeOrders(marketplaceId);
        Snapshot<List<Order>> trades = flexemarkets.recentTrades(marketplaceId);

        // Clear before reseeding so a resync (Phase 2b) doesn't
        // double-add against existing price levels. Initial seed
        // hits empty books so clear() is a no-op there.
        this.orderBooks.clear();
        this.trades.clear();

        // Snapshot orders are all available (consumer == null), so
        // MarketBook.update treats them as adds — same code path WS
        // deltas use. Trades snapshot feeds the tape via the same
        // update() entrypoint.
        if (!orders.body().isEmpty()) {
            this.orderBooks.update(orders.body().toArray(new Order[0]));
        }
        if (!trades.body().isEmpty()) {
            this.trades.update(trades.body().toArray(new Order[0]));
        }

        // Use the orders snapshot's seq as the watermark — orders and
        // trades flow through the same delta stream, so they share a
        // single seq. The trades snapshot's seq is informational.
        this.lastAppliedSeq = orders.asOfSeq();
    }

    @Override
    public long marketplaceId() {
        return marketplaceId;
    }

    @Override
    public List<Market> markets() {
        _ensureOpen();
        return markets;
    }

    @Override
    public MarketBook orderBook(long marketId) {
        _ensureOpen();
        return orderBooks.get(marketId);
    }

    @Override
    public Trades trades(long marketId) {
        _ensureOpen();
        return trades.get(marketId);
    }

    @Override
    public Session session() {
        _ensureOpen();
        return session.get();
    }

    @Override
    public Holding holding() {
        _ensureOpen();
        return holding.get();
    }

    @Override
    public Subscription onSessionChange(Consumer<Session> handler) {
        _ensureOpen();
        sessionHandlers.add(handler);
        return () -> sessionHandlers.remove(handler);
    }

    @Override
    public Subscription onOrderBookChange(long marketId, Consumer<MarketBook> handler) {
        _ensureOpen();
        OrderBookHandler entry = new OrderBookHandler(marketId, handler);
        bookHandlers.add(entry);
        return () -> bookHandlers.remove(entry);
    }

    @Override
    public Subscription onTrade(long marketId, Consumer<fm.Trade> handler) {
        _ensureOpen();
        TradeHandler entry = new TradeHandler(marketId, handler);
        tradeHandlers.add(entry);
        return () -> tradeHandlers.remove(entry);
    }

    @Override
    public Subscription onHoldingChange(Consumer<Holding> handler) {
        _ensureOpen();
        holdingHandlers.add(handler);
        return () -> holdingHandlers.remove(handler);
    }

    @Override
    public Subscription onGap(Consumer<GapEvent> handler) {
        _ensureOpen();
        gapHandlers.add(handler);
        return () -> gapHandlers.remove(handler);
    }

    @Override
    public Subscription onReconnect(Consumer<ReconnectEvent> handler) {
        _ensureOpen();
        reconnectHandlers.add(handler);
        return () -> reconnectHandlers.remove(handler);
    }

    @Override
    public Order submitLimit(long marketId, Side side, long units, long price) {
        _ensureOpen();
        return flexemarkets.submitLimit(marketplaceId, marketId, side, units, price);
    }

    @Override
    public Order submitCancel(long marketId, long originalId) {
        _ensureOpen();
        return flexemarkets.submitCancel(marketplaceId, marketId, originalId);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        dispatcher.interrupt();
        try { events.close(); } catch (Throwable ignored) { /* best-effort */ }
        // The Flexemarkets instance is owned by the caller; we don't
        // close it. If observe(...) was the only consumer, the caller
        // can close Flexemarkets themselves.
    }

    private void _drain() {
        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                Object event = queue.poll(1, TimeUnit.SECONDS);
                if (event == null) continue;

                if (event instanceof OrdersUpdate update) {
                    _processOrdersUpdate(update);
                } else if (event instanceof Session s) {
                    session.set(s);
                    for (var h : sessionHandlers) h.accept(s);
                } else if (event instanceof Holding h) {
                    holding.set(h);
                    for (var hh : holdingHandlers) hh.accept(h);
                } else if (event instanceof StreamDropped error) {
                    // The subscription restores itself; nothing to do but say so.
                    System.err.println("[MarketView] WS transport error on marketplace "
                            + marketplaceId + ": " + error.failure().getMessage());
                } else if (event instanceof Reconnected) {
                    _reseedAfterReconnect();
                } else if (event instanceof FrameUnreadable ex) {
                    // STOMP ERROR / parse failure. Logged for
                    // visibility; reconnecting won't help with a
                    // malformed frame, so we leave the view as-is.
                    System.err.println("[MarketView] WS error on marketplace "
                            + marketplaceId + ": " + ex.message());
                }
                // VERSION and SESSION-LIST aren't reflected in the
                // public surface yet; ignore.
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Converge state after the subscription has restored itself.
     *
     * <p>A reconnect is just the largest possible gap, so the same snapshot
     * reseed that recovers from a missed sequence recovers from this. The view
     * no longer performs the reconnect: whether the stream is up belongs to the
     * subscription, and driving its retry loop from this thread meant the
     * dispatcher sat blocked in sleeps while events queued behind it.
     *
     * <p>A failure here is a failed <em>reseed</em>, not a failed reconnect —
     * the stream is live and the view is stale, which is worth telling handlers
     * apart from a dead connection.
     */
    private void _reseedAfterReconnect() {
        ReconnectEvent event;
        try {
            _seedFromSnapshot();
            event = new ReconnectEvent(marketplaceId, true, null);
        } catch (Throwable t) {
            System.err.println("[MarketView] Reseed failed on marketplace "
                    + marketplaceId + "; view is stale: " + t.getMessage());
            event = new ReconnectEvent(marketplaceId, false, t.getMessage());
        }
        for (var h : reconnectHandlers) {
            try { h.accept(event); } catch (Throwable ignored) { /* don't let one bad handler stop dispatch */ }
        }
    }

    /**
     * Phase 2b gap recovery + Phase 2a filter. When a delta arrives
     * with {@code seq > lastAppliedSeq + 1}, one or more frames were
     * dropped between us and fm-server: re-fetch the V1 snapshot,
     * clear local state, reseed, then let the filter below skip the
     * triggering delta if the new {@code asOfSeq} already covers it.
     */
    private void _processOrdersUpdate(OrdersUpdate update) {
        if (update.seq() != Snapshot.NO_SEQ
                && lastAppliedSeq != Snapshot.NO_SEQ
                && update.seq() > lastAppliedSeq + 1) {
            long expectedSeq = lastAppliedSeq + 1;
            System.err.println("[MarketView] ORDERS-UPDATE seq gap on marketplace "
                    + marketplaceId + " — expected " + expectedSeq
                    + ", got " + update.seq() + "; resyncing from snapshot");
            GapEvent event = new GapEvent(marketplaceId, expectedSeq, update.seq());
            for (var h : gapHandlers) {
                try { h.accept(event); } catch (Throwable ignored) { /* don't let one bad handler stop recovery */ }
            }
            _seedFromSnapshot();
        }

        if (update.seq() != Snapshot.NO_SEQ
                && lastAppliedSeq != Snapshot.NO_SEQ
                && update.seq() <= lastAppliedSeq) {
            return;
        }

        Order[] orders = update.orders();
        var touched = _marketIdsTouched(orders);
        orderBooks.update(orders);
        var traded = trades.update(orders);

        // Trades first: the more specific event, and a book handler that then
        // reads the tape sees the same trade the trade handler was just given.
        // Both aggregators are already current either way -- what is ordered
        // here is only which handler hears about it first.
        for (var market : traded.entrySet()) {
            for (var h : tradeHandlers) {
                if (h.marketId == market.getKey()) {
                    market.getValue().forEach(h.handler);
                }
            }
        }

        for (long marketId : touched) {
            var book = orderBooks.get(marketId);
            if (book == null) continue;
            for (var h : bookHandlers) {
                if (h.marketId == marketId) h.handler.accept(book);
            }
        }
        if (update.seq() != Snapshot.NO_SEQ) {
            lastAppliedSeq = update.seq();
        }
    }

    private long[] _marketIdsTouched(Order[] orders) {
        // Small array: scan once, dedupe on the fly without allocating
        // a Set. Marketplace-typical 1-3 markets per update.
        long[] ids = new long[Math.min(orders.length, 16)];
        int n = 0;
        outer:
        for (var o : orders) {
            long id = o.marketId();
            for (int i = 0; i < n; i++) if (ids[i] == id) continue outer;
            if (n == ids.length) {
                long[] grown = new long[n * 2];
                System.arraycopy(ids, 0, grown, 0, n);
                ids = grown;
            }
            ids[n++] = id;
        }
        long[] result = new long[n];
        System.arraycopy(ids, 0, result, 0, n);
        return result;
    }

    private void _ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MarketView for marketplace " + marketplaceId + " is closed");
        }
    }

    private record OrderBookHandler(long marketId, Consumer<MarketBook> handler) {}

    private record TradeHandler(long marketId, Consumer<fm.Trade> handler) {}
}
