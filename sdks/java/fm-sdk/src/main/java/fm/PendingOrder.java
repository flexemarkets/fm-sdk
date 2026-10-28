package fm;

import fm.model.Order;
import java.util.concurrent.CompletableFuture;


/**
 * An order sent over the socket, not yet answered.
 *
 * <p>Completes with the {@link Order} fm-server made, or exceptionally with
 * what {@link fm.role.Writing#submitLimit} would have thrown for its refusal --
 * or {@link fm.error.OrderOutcomeUnknownException} if the stream closed before
 * the answer came. Returned by {@link fm.role.Writing#sendLimit}.
 */
public final class PendingOrder extends CompletableFuture<Order> {

    private final String _submissionId;

    /**
     * An order sent under {@code submissionId}, not yet answered.
     *
     * @param submissionId the id it was sent under
     */
    public PendingOrder(String submissionId) {
        this._submissionId = submissionId;
    }

    /**
     * The id the order was sent under, which fm-server keeps on the order.
     *
     * @return the submission id
     */
    public String submissionId() {
        return _submissionId;
    }
}
