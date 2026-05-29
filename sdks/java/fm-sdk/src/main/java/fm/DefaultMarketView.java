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

    DefaultMarketView(Flexemarkets flexemarkets, long marketplaceId, List<Market> markets) {
        this.flexemarkets = flexemarkets;
        this.marketplaceId = marketplaceId;
        this.markets = List.copyOf(markets);
        this.orderBooks = new OrderBooks(this.markets);
        // 100 matches the default per-market Trades capacity — see
        // Trades(Market) ctor. Plumb through to observe() later if a
        // caller needs deeper trade scrollback.
        this.trades = new MarketplaceTrades(this.markets, 100);

        flexemarkets.listen(marketplaceId, queue);
        this.dispatcher = Thread.startVirtualThread(this::_drain);
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

                if (event instanceof Order[] orders) {
                    // Find which markets were touched so we only fire
                    // those books' handlers, not every book's.
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
                } else if (event instanceof Session s) {
                    session.set(s);
                    for (var h : sessionHandlers) h.accept(s);
                } else if (event instanceof Holding h) {
                    holding.set(h);
                    for (var hh : holdingHandlers) hh.accept(h);
                } else if (event instanceof WsTransportError || event instanceof WsException) {
                    // Reconnect handling lands in Phase 2. For now the
                    // dispatcher exits and the view becomes useless;
                    // callers must close() and observe() again.
                    break;
                }
                // VERSION and SESSION-LIST aren't reflected in the
                // public surface yet; ignore.
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
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
