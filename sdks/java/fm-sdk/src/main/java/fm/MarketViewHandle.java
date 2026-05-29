package fm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Order;
import fm.Types.Session;

/**
 * Reader-side handle on a refcounted {@link DefaultMarketView}.
 * Returned by {@link Flexemarkets#observe(long)}; multiple handles for
 * the same {@code marketplaceId} share one underlying view + WS
 * subscription + materialized state. Each handle's {@link #close()}
 * decrements the shared refcount and tears down the shared resources
 * on the last close.
 *
 * <p>Subscriptions registered via {@code on*Change} on this handle
 * are tracked locally and closed when the handle closes — so a
 * handler doesn't keep firing into stale state after the handle is
 * gone.
 */
class MarketViewHandle implements MarketView {
    private final DefaultMarketView shared;
    private final Runnable onClose;
    private final List<Subscription> mySubscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    MarketViewHandle(DefaultMarketView shared, Runnable onClose) {
        this.shared = shared;
        this.onClose = onClose;
    }

    @Override public long marketplaceId() {
        return shared.marketplaceId();
    }

    @Override public List<Market> markets() {
        _check();
        return shared.markets();
    }

    @Override public OrderBook orderBook(long marketId) {
        _check();
        return shared.orderBook(marketId);
    }

    @Override public Session session() {
        _check();
        return shared.session();
    }

    @Override public Holding holding() {
        _check();
        return shared.holding();
    }

    @Override public Subscription onSessionChange(Consumer<Session> handler) {
        _check();
        Subscription sub = shared.onSessionChange(handler);
        mySubscriptions.add(sub);
        return sub;
    }

    @Override public Subscription onOrderBookChange(long marketId, Consumer<OrderBook> handler) {
        _check();
        Subscription sub = shared.onOrderBookChange(marketId, handler);
        mySubscriptions.add(sub);
        return sub;
    }

    @Override public Subscription onHoldingChange(Consumer<Holding> handler) {
        _check();
        Subscription sub = shared.onHoldingChange(handler);
        mySubscriptions.add(sub);
        return sub;
    }

    @Override public Order submitLimit(long marketId, String side, long units, long price) {
        _check();
        return shared.submitLimit(marketId, side, units, price);
    }

    @Override public Order submitCancel(long marketId, long originalId) {
        _check();
        return shared.submitCancel(marketId, originalId);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        // Close subscriptions this handle registered so handlers
        // don't fire after the handle is gone. Subscription.close()
        // is idempotent per the contract.
        for (var sub : mySubscriptions) {
            try { sub.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
        mySubscriptions.clear();
        onClose.run();
    }

    private void _check() {
        if (closed) {
            throw new IllegalStateException(
                    "MarketView handle for marketplace " + shared.marketplaceId() + " is closed");
        }
    }
}
