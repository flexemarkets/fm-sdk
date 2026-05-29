package fm;

import fm.Types.Order;

/**
 * One ORDERS-UPDATE delta from the WS stream, carrying the parsed
 * order array plus the per-marketplace {@code seq} header fm-server
 * stamps each frame with (commit {@code c6eea6eca}).
 *
 * <p>Consumers reconcile this against a REST {@link Snapshot}: apply
 * deltas whose {@link #seq()} is greater than the snapshot's
 * {@code asOfSeq} and skip those whose seq is less than or equal.
 *
 * <p>{@link #seq()} is {@link Snapshot#NO_SEQ} when the server didn't
 * stamp the header (older fm-server). Consumers that don't need
 * snapshot reconciliation can still pattern-match on the orders
 * field; in that case the value of {@code seq} is irrelevant.
 *
 * <p>Note: this <em>replaces</em> raw {@code Order[]} in the
 * {@code Flexemarkets.listen(queue)} pipe. Callers previously
 * doing {@code case Order[] orders -> ...} should switch to
 * {@code case OrdersUpdate update -> ...} and read
 * {@code update.orders()}.
 */
public record OrdersUpdate(Order[] orders, long seq) {
}
