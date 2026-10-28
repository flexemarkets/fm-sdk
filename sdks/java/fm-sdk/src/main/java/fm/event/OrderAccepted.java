package fm.event;

import fm.model.Order;


/**
 * An order sent over the socket was already accepted: the answer to a resend
 * of a submission fm-server holds, carrying the order it made then.
 *
 * <p>A first submission is not answered this way. Its acceptance is the
 * ORDERS-UPDATE fan-out, whose order carries the submission id; a resend makes
 * no order, so it is in no fan-out, and this is its answer.
 *
 * @param submissionId the submission this answers, or null if the frame did not say
 * @param order        the order fm-server made for it
 */
public record OrderAccepted(String submissionId, Order order) {
}
