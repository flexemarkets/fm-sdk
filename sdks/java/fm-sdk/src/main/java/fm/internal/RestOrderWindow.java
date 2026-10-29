package fm.internal;

import fm.PendingOrder;
import fm.error.ConnectionFailedException;
import fm.error.FlexemarketsException;
import fm.error.OrderOutcomeUnknownException;
import fm.model.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orders over REST, several to a request (fm-sdk#7, docs/SUBMIT-RATE.md, section 5).
 *
 * <p>What {@link OrderStream} is for the socket, for a client connected with
 * {@link fm.OrderRoute#REST}. One request is in flight at a time; orders sent
 * while it is out wait in a list, and go together as the next
 * {@code POST .../orders/batch} once it is answered. One at a time this is
 * {@code submitLimit}; in a burst the requests grow to {@link #MOST_WAITING}.
 *
 * <p>One request, not several: two lists in flight could reach the book out of
 * order, which is what this exists to prevent. fm-server runs each list in the
 * order given, so orders reach the exchange in the order sent.
 *
 * <p>As Python's rest_orders and TypeScript's rest-orders.ts.
 */
final class RestOrderWindow {

    /**
     * The most orders that wait for the next request; a caller sending more
     * waits for room. The most one request carries, so in a burst every request
     * is full.
     */
    static final int MOST_WAITING = 64;

    /** One order's answer in a batch: the order it made, or the refusal it would have thrown. */
    record Outcome(Order order, FlexemarketsException refusal) {}

    /**
     * Posts a list of orders and answers each, in order. Sends once more, whole,
     * when the answer is lost; throws when the request as a whole fails.
     */
    @FunctionalInterface
    interface Post {
        List<Outcome> post(List<Map<String, Object>> batch);
    }

    private record Waiting(Map<String, Object> order, PendingOrder pending) {}

    private final Post _post;
    private final long _marketplaceId;
    private final int _mostWaiting;
    private final Object _room = new Object();
    private List<Waiting> _waiting = new ArrayList<>();
    private boolean _sending;
    private boolean _closed;

    RestOrderWindow(Post post, long marketplaceId) {
        this(post, marketplaceId, MOST_WAITING);
    }

    RestOrderWindow(Post post, long marketplaceId, int mostWaiting) {
        this._post = post;
        this._marketplaceId = marketplaceId;
        this._mostWaiting = mostWaiting;
    }

    /**
     * Queue {@code order} for the next request, waiting while the list is full.
     *
     * @throws ConnectionFailedException once the window is closed
     */
    PendingOrder submit(String submissionId, Map<String, Object> order) {
        var pending = new PendingOrder(submissionId);
        synchronized (_room) {
            while (!_closed && _waiting.size() >= _mostWaiting) {
                try {
                    _room.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ConnectionFailedException("Interrupted while waiting to send; the order was not sent.");
                }
            }
            if (_closed) {
                throw new ConnectionFailedException("The client is closed; the order was not sent.");
            }
            _waiting.add(new Waiting(order, pending));
            if (!_sending) {
                _sending = true;
                var sender = new Thread(this::_send, "fm-rest-orders-" + _marketplaceId);
                sender.setDaemon(true);
                sender.start();
            }
        }
        return pending;
    }

    /** Refuse what waits as not sent; a request in flight is answered as it lands. */
    void close() {
        List<Waiting> waiting;
        synchronized (_room) {
            _closed = true;
            waiting = _waiting;
            _waiting = new ArrayList<>();
            _room.notifyAll();
        }
        for (var w : waiting) {
            w.pending().completeExceptionally(
                    new ConnectionFailedException("The client closed before the order was sent."));
        }
    }

    /** Send what waits, a list at a time, until nothing does. */
    private void _send() {
        while (true) {
            List<Waiting> batch;
            synchronized (_room) {
                if (_waiting.isEmpty()) {
                    _sending = false;
                    return;
                }
                batch = _waiting;
                _waiting = new ArrayList<>();
                _room.notifyAll();
            }
            _answer(batch);
        }
    }

    private void _answer(List<Waiting> batch) {
        List<Outcome> outcomes;
        try {
            outcomes = _post.post(batch.stream().map(Waiting::order).toList());
        } catch (RuntimeException failed) {
            // A 4xx is a refusal of the request as a whole -- the token, the
            // route -- before any of it ran, so none was placed: each gets the
            // refusal, as submitLimit would have thrown it.
            if (failed instanceof FlexemarketsException refused && null != refused.statusCode()
                    && refused.statusCode() >= 400 && refused.statusCode() < 500) {
                for (var w : batch) {
                    w.pending().completeExceptionally(refused);
                }
                return;
            }
            // Anything else -- sent twice without an answer, a 5xx, an answer
            // that cannot be read -- may come after some of the list was placed.
            // Unknown, never refused: a refusal invites placing it again, and if
            // the first had landed, holding the position twice.
            for (var w : batch) {
                w.pending().completeExceptionally(new OrderOutcomeUnknownException(
                        "No readable answer to the request carrying it: " + failed
                        + ". Read the book to learn whether it stands.", w.pending().submissionId()));
            }
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            var outcome = outcomes.get(i);
            if (null != outcome.refusal()) {
                batch.get(i).pending().completeExceptionally(outcome.refusal());
            } else {
                batch.get(i).pending().complete(outcome.order());
            }
        }
    }
}
