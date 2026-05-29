package fm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import fm.Events.WsException;
import fm.Events.WsTransportError;
import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Order;
import fm.Types.Session;

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
 *   <li>Dispatches WS events into the existing {@link OrderBooks}
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
 *   <li>Reconnect handling — a {@link WsTransportError} from the
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

    private final OrderBooks orderBooks;
    private final MarketplaceTrades trades;
    private final AtomicReference<Session> session = new AtomicReference<>();
    private final AtomicReference<Holding> holding = new AtomicReference<>();

    // CopyOnWriteArrayList because handler arrays are read once per
    // dispatch (hot path) and mutated rarely (register / unregister
    // happens on robot startup / shutdown).
    private final List<Consumer<Session>>                  sessionHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<Holding>>                  holdingHandlers = new CopyOnWriteArrayList<>();
    private final List<OrderBookHandler>                   bookHandlers    = new CopyOnWriteArrayList<>();

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(10_000);
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

    DefaultMarketView(Flexemarkets flexemarkets, long marketplaceId, List<Market> markets) {
        this.flexemarkets = flexemarkets;
        this.marketplaceId = marketplaceId;
        this.markets = List.copyOf(markets);
        this.orderBooks = new OrderBooks(this.markets);
        // 100 matches the default per-market Trades capacity — see
        // Trades(Market) ctor. Plumb through to observe() later if a
        // caller needs deeper trade scrollback.
        this.trades = new MarketplaceTrades(this.markets, 100);

        // Subscribe WS first so deltas start landing in the queue,
        // then fetch the REST snapshot, apply it, and only THEN start
        // the dispatcher. Any deltas that arrive between listen() and
        // snapshot apply sit in the queue and get filtered by seq
        // when the dispatcher runs.
        flexemarkets.listen(marketplaceId, queue);
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
        Snapshot<List<Types.Order>> orders = flexemarkets.activeOrdersV1(marketplaceId);
        Snapshot<List<Types.Order>> trades = flexemarkets.recentTradesV1(marketplaceId);

        // Clear before reseeding so a resync (Phase 2b) doesn't
        // double-add against existing price levels. Initial seed
        // hits empty books so clear() is a no-op there.
        this.orderBooks.clear();
        this.trades.clear();

        // Snapshot orders are all available (consumer == null), so
        // OrderBook.update treats them as adds — same code path WS
        // deltas use. Trades snapshot feeds the tape via the same
        // update() entrypoint.
        if (!orders.body().isEmpty()) {
            this.orderBooks.update(orders.body().toArray(new Types.Order[0]));
        }
        if (!trades.body().isEmpty()) {
            this.trades.update(trades.body().toArray(new Types.Order[0]));
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
    public OrderBook orderBook(long marketId) {
        _ensureOpen();
        return orderBooks.get(marketId);
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
    public Subscription onOrderBookChange(long marketId, Consumer<OrderBook> handler) {
        _ensureOpen();
        OrderBookHandler entry = new OrderBookHandler(marketId, handler);
        bookHandlers.add(entry);
        return () -> bookHandlers.remove(entry);
    }

    @Override
    public Subscription onHoldingChange(Consumer<Holding> handler) {
        _ensureOpen();
        holdingHandlers.add(handler);
        return () -> holdingHandlers.remove(handler);
    }

    @Override
    public Order submitLimit(long marketId, String side, long units, long price) {
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
                } else if (event instanceof WsTransportError) {
                    _handleTransportError();
                } else if (event instanceof WsException ex) {
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
     * Phase 2c auto-reconnect. On a WsTransportError, reconnect the
     * underlying WS and re-seed from the V1 snapshot — reconnect is
     * just the largest possible gap, so 2b's recovery machinery
     * handles the state convergence. One reconnect attempt; if it
     * fails the view is left stale and the caller's next access will
     * see whatever state was last applied. More sophisticated
     * backoff/retry can layer on later.
     */
    private void _handleTransportError() {
        System.err.println("[MarketView] WS transport error on marketplace "
                + marketplaceId + "; reconnecting");
        try {
            flexemarkets.reconnect();
            _seedFromSnapshot();
        } catch (Throwable t) {
            System.err.println("[MarketView] Reconnect failed on marketplace "
                    + marketplaceId + "; view is stale: " + t.getMessage());
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
            System.err.println("[MarketView] ORDERS-UPDATE seq gap on marketplace "
                    + marketplaceId + " — expected " + (lastAppliedSeq + 1)
                    + ", got " + update.seq() + "; resyncing from snapshot");
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
        trades.update(orders);
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

    private record OrderBookHandler(long marketId, Consumer<OrderBook> handler) {}
}
