package fm.internal;

import fm.PendingOrder;
import fm.error.ConnectionFailedException;
import fm.error.FlexemarketsException;
import fm.error.OrderOutcomeUnknownException;
import fm.event.OrderAccepted;
import fm.event.OrderRejected;
import fm.event.OrdersUpdate;
import fm.event.StreamDropped;
import fm.event.StreamReconnected;
import fm.model.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


/**
 * Orders over the socket, several in flight (fm-sdk#7, docs/SUBMIT-RATE.md).
 *
 * <p>A REST order waits for its answer before the next can go, so a robot's
 * order rate is one over the round trip. fm-server keeps the orders one socket
 * sends in the order it sent them (its SocketOrderSequence), so a client may
 * send the next before the last is answered and still have them matched in
 * sequence. That is what this does: each order goes out as a STOMP SEND at once,
 * and its answer comes back on the stream -- the ORDERS-UPDATE fan-out for an
 * order placed, ORDER-REJECTED for one refused, ORDER-ACCEPTED for a resend of
 * one already placed -- matched to it by the submission id it was sent with.
 *
 * <p>While the socket is down an order is held rather than sent another way,
 * and every unanswered order is sent again, in the order first submitted, once
 * it is back. Sending the held ones over REST meanwhile would let them overtake
 * orders sent before the drop, and in-order is the point. An order that has
 * not gone out at all is held for at most {@link #HOLD_MS}, then refused as not
 * sent; one sent before the drop may have been placed, and is sent again
 * whenever the socket returns.
 *
 * <p>As Python's order_stream and TypeScript's order-stream.ts.
 */
final class OrderStream {

    /**
     * How many orders may be unanswered at once before {@link #submit} waits for
     * one. Bounds what a burst puts in front of the server and on the user's
     * 4 MB per-session send buffer, where the answers and the marketplace's
     * fan-out queue together. A first guess; fm-server's W3 load run should set it.
     */
    static final int MAX_IN_FLIGHT = 64;

    /**
     * The longest an order socket waits between attempts to come back: five
     * seconds, where a subscription waits up to a minute. Orders are waiting on
     * it, and every second it waits is a second they sit unsent -- an outage ran
     * 24-33 s past the server's return on the minute's back-off. It still
     * doubles from 2 s and is drawn from the upper half, so a classroom's robots
     * do not come back in step; and an order socket asks for no history, which
     * is what the minute spares the server.
     */
    static final long RECONNECT_MAX_MS = 5_000;

    /** What an order stream needs of its event listener. */
    interface Listener {
        void send(String destination, String body);
        void close();
        boolean isClosed();
    }

    /**
     * The longest an order waits, unsent, for the order socket to come back.
     * Long enough for a reconnect after a blip (the socket retries within
     * {@link #RECONNECT_MAX_MS}); after that the order is refused as not sent,
     * as REST refused it with a 503 during an outage. Held for the whole outage
     * instead, a robot's quotes went onto the book up to a minute and a half
     * after it sent them -- stale, and on top of the ones it sent again once it
     * saw the stream come back.
     */
    static final long HOLD_MS = 5_000;

    private static final Object STOP = new Object();
    private static final Object WAKE = new Object();

    private final String _destination;
    private final BlockingQueue<Object> _events = new LinkedBlockingQueue<>();
    /** Fair, so callers waiting for a place get it in the order they asked, and their orders go out in that order. */
    private final Semaphore _window;
    /** Taken around every send and every change to what is unanswered. */
    private final Object _lock = new Object();
    /** In submission order. */
    private final Map<String, Unanswered> _unanswered = new LinkedHashMap<>();
    private final Listener _listener;
    private final long _holdNanos;
    private final Thread _dispatcher;
    private boolean _up = true;
    private boolean _closed;

    private static final class Unanswered {
        final PendingOrder pending;
        final String body;
        /** Whether a send of it was attempted; one that never was cannot have been placed. */
        boolean sent;
        /** When it began waiting, unsent, for the socket ({@link System#nanoTime()}); -1 when it is not. */
        long heldSince = -1;

        Unanswered(PendingOrder pending, String body) {
            this.pending = pending;
            this.body = body;
        }
    }

    /**
     * Open the stream.
     *
     * @param connect       starts a listener delivering onto the queue it is given
     * @param marketplaceId the marketplace the orders are for
     * @param maxInFlight   how many may be unanswered at once
     */
    OrderStream(Function<BlockingQueue<Object>, Listener> connect, long marketplaceId, int maxInFlight) {
        this(connect, marketplaceId, maxInFlight, HOLD_MS);
    }

    /** As above, holding an unsent order at most {@code holdMillis}. */
    OrderStream(Function<BlockingQueue<Object>, Listener> connect, long marketplaceId, int maxInFlight,
                long holdMillis) {
        this._holdNanos = TimeUnit.MILLISECONDS.toNanos(holdMillis);
        this._destination = "/app/marketplaces/" + marketplaceId + "/orders";
        this._window = new Semaphore(maxInFlight, true);
        this._listener = connect.apply(_events);
        this._dispatcher = Thread.ofPlatform().daemon().name("fm-order-stream").start(this::_dispatch);
    }

    /**
     * Send {@code body}, an order naming {@code submissionId}, and return at once.
     *
     * <p>Waits only while {@link #MAX_IN_FLIGHT} orders are unanswered.
     *
     * @throws ConnectionFailedException once the stream is closed
     */
    PendingOrder submit(String submissionId, String body) {
        _window.acquireUninterruptibly();
        synchronized (_lock) {
            if (_closed) {
                _window.release();
                throw new ConnectionFailedException("The order stream is closed; the order was not sent.");
            }
            var pending = new PendingOrder(submissionId);
            pending.whenComplete((order, failure) -> _window.release());
            var entry = new Unanswered(pending, body);
            _unanswered.put(submissionId, entry);
            if (_up) {
                _send(entry);
            } else {
                entry.heldSince = System.nanoTime();
                _events.offer(WAKE);  // so the dispatcher times its hold
            }
            return pending;
        }
    }

    /**
     * Stop, and fail every unanswered order: one that was sent with
     * {@link OrderOutcomeUnknownException}, one that was not with
     * {@link ConnectionFailedException}.
     */
    void close() {
        close(null);
    }

    private void close(Throwable cause) {
        java.util.List<Unanswered> left;
        synchronized (_lock) {
            if (_closed) {
                return;
            }
            _closed = true;
            left = new ArrayList<>(_unanswered.values());
            _unanswered.clear();
        }
        _listener.close();
        _events.offer(STOP);
        for (var entry : left) {
            var id = entry.pending.submissionId();
            FlexemarketsException error = entry.sent
                    ? new OrderOutcomeUnknownException("Order " + id + " was sent and its stream closed before "
                            + "the answer; it may have been placed.", id)
                    : new ConnectionFailedException("Order " + id + " was not sent: its stream closed first.");
            if (cause != null) {
                error.initCause(cause);
            }
            entry.pending.completeExceptionally(error);
        }
    }

    // --- the dispatcher thread ---

    /** Send one, under the lock. A failure leaves it held for the reconnect. */
    private void _send(Unanswered entry) {
        entry.sent = true;
        entry.heldSince = -1;
        try {
            _listener.send(_destination, entry.body);
        } catch (RuntimeException notSent) {
            _up = false;
        }
    }

    private void _dispatch() {
        while (true) {
            Object event;
            try {
                long wait = _untilAHoldEnds();
                event = wait < 0 ? _events.take() : _events.poll(wait, TimeUnit.NANOSECONDS);
                if (event == null) {
                    event = WAKE;
                }
                _refuseLongHeld();
            } catch (InterruptedException e) {
                // No answer can be matched from here on: say so to every order
                // still waiting, rather than leave them waiting for ever.
                close(e);
                return;
            }
            if (event == STOP) {
                return;
            }
            switch (event) {
                case OrdersUpdate update -> {
                    for (var order : update.orders()) {
                        if (order.submissionId() != null) {
                            _answer(order.submissionId(), order, null);
                        }
                    }
                }
                case OrderAccepted accepted when accepted.submissionId() != null ->
                        _answer(accepted.submissionId(), accepted.order(), null);
                case OrderRejected rejected when rejected.submissionId() != null ->
                        _answer(rejected.submissionId(), null, rejected.error());
                case StreamDropped dropped -> {
                    if (_listener.isClosed()) {
                        // The listener has given up: its token was refused twice.
                        close(dropped.failure());
                        return;
                    }
                    synchronized (_lock) {
                        _up = false;
                    }
                }
                case StreamReconnected reconnected -> {
                    synchronized (_lock) {
                        _up = true;
                        for (var entry : _unanswered.values()) {
                            if (!_up) {
                                break;
                            }
                            _send(entry);
                        }
                    }
                }
                default -> { }
            }
        }
    }

    /** Nanoseconds until the longest-held unsent order has waited its limit; -1 if none is held. */
    private long _untilAHoldEnds() {
        long earliest = Long.MAX_VALUE;
        synchronized (_lock) {
            for (var entry : _unanswered.values()) {
                if (entry.heldSince >= 0) {
                    earliest = Math.min(earliest, entry.heldSince);
                }
            }
        }
        return earliest == Long.MAX_VALUE ? -1 : Math.max(0, earliest + _holdNanos - System.nanoTime());
    }

    /** Refuse, as not sent, every order that has waited unsent for the socket longer than the hold. */
    private void _refuseLongHeld() {
        long now = System.nanoTime();
        var expired = new ArrayList<Unanswered>();
        synchronized (_lock) {
            for (var it = _unanswered.values().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.heldSince >= 0 && now - entry.heldSince >= _holdNanos) {
                    expired.add(entry);
                    it.remove();
                }
            }
        }
        // Outside the lock: a caller's continuation may submit again.
        for (var entry : expired) {
            entry.pending.completeExceptionally(new ConnectionFailedException("Order " + entry.pending.submissionId()
                    + " was not sent: the order socket was down for more than "
                    + TimeUnit.NANOSECONDS.toMillis(_holdNanos) / 1000.0 + " s."));
        }
    }

    private void _answer(String submissionId, Order order, Throwable failure) {
        Unanswered entry;
        synchronized (_lock) {
            entry = _unanswered.remove(submissionId);
        }
        // Outside the lock: a caller's continuation may submit again.
        if (entry == null) {
            return;
        }
        if (failure != null) {
            entry.pending.completeExceptionally(failure);
        } else {
            entry.pending.complete(order);
        }
    }

    /** For tests: the queue the listener delivers to. */
    BlockingQueue<Object> events() {
        return _events;
    }

    /** For tests: whether orders are going out now, or being held. */
    boolean isUp() {
        synchronized (_lock) {
            return _up;
        }
    }

    /** For tests: the listener the orders go out on. */
    Listener listener() {
        return _listener;
    }

    /** For tests: the dispatcher, to see it end. */
    Thread dispatcher() {
        return _dispatcher;
    }
}
