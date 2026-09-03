package fm.internal;

import fm.event.GapEvent;
import fm.event.ReconnectEvent;
import fm.model.Holding;
import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.Session;
import fm.model.Trade;
import fm.Flexemarkets;
import fm.MarketView;
import fm.Book;
import fm.Tape;
import fm.Subscription;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;


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
    private final DefaultMarketView _shared;
    private final Runnable _onClose;
    private final List<Subscription> _mySubscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean _closed;

    MarketViewHandle(DefaultMarketView shared, Runnable onClose) {
        this._shared = shared;
        this._onClose = onClose;
    }

    @Override public long marketplaceId() {
        return _shared.marketplaceId();
    }

    @Override public List<Market> markets() {
        _check();
        return _shared.markets();
    }

    @Override public Book book(long marketId) {
        _check();
        return _shared.book(marketId);
    }

    @Override public Tape tape(long marketId) {
        _check();
        return _shared.tape(marketId);
    }

    @Override public Session session() {
        _check();
        return _shared.session();
    }

    @Override public Holding holding() {
        _check();
        return _shared.holding();
    }

    @Override public Subscription onSessionChange(Consumer<Session> handler) {
        _check();
        Subscription sub = _shared.onSessionChange(handler);
        _mySubscriptions.add(sub);
        return sub;
    }

    @Override public Subscription onBookChange(long marketId, Consumer<Book> handler) {
        _check();
        Subscription sub = _shared.onBookChange(marketId, handler);
        _mySubscriptions.add(sub);
        return sub;
    }

    @Override public Subscription onTrade(long marketId, Consumer<fm.model.Trade> handler) {
        _check();
        Subscription sub = _shared.onTrade(marketId, handler);
        _mySubscriptions.add(sub);
        return sub;
    }

    @Override public Subscription onHoldingChange(Consumer<Holding> handler) {
        _check();
        Subscription sub = _shared.onHoldingChange(handler);
        _mySubscriptions.add(sub);
        return sub;
    }

    @Override public Subscription onGap(Consumer<GapEvent> handler) {
        _check();
        Subscription sub = _shared.onGap(handler);
        _mySubscriptions.add(sub);
        return sub;
    }

    @Override public Subscription onReconnect(Consumer<ReconnectEvent> handler) {
        _check();
        Subscription sub = _shared.onReconnect(handler);
        _mySubscriptions.add(sub);
        return sub;
    }

    @Override public Order submitLimit(long marketId, OrderSide side, long units, long price) {
        _check();
        return _shared.submitLimit(marketId, side, units, price);
    }

    @Override public Order submitCancel(long marketId, long originalId) {
        _check();
        return _shared.submitCancel(marketId, originalId);
    }

    @Override public void close() {
        if (_closed) return;
        _closed = true;
        // Close subscriptions this handle registered so handlers
        // don't fire after the handle is gone. Subscription.close()
        // is idempotent per the contract.
        for (var sub : _mySubscriptions) {
            try { sub.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
        _mySubscriptions.clear();
        _onClose.run();
    }

    private void _check() {
        if (_closed) {
            throw new IllegalStateException(
                    "MarketView handle for marketplace " + _shared.marketplaceId() + " is closed");
        }
    }
}
