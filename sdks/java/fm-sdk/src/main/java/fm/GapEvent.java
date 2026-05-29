package fm;

/**
 * Fires when {@link MarketView} detects a sequence-gap in the
 * ORDERS-UPDATE WS stream — one or more frames were dropped between
 * the client and fm-server. After a gap, {@code MarketView} re-runs
 * the REST snapshot recovery flow before applying further deltas; the
 * gap event is the signal callers can wire into their own
 * observability stack (errors dashboard, metrics, alerting) instead
 * of relying on the SDK's default stderr log.
 *
 * <p>Subscribe via {@link MarketView#onGap(java.util.function.Consumer)}.
 *
 * @param marketplaceId the marketplace the gap was detected on
 * @param expectedSeq   the seq the next delta was expected to carry
 *                      ({@code lastAppliedSeq + 1})
 * @param receivedSeq   the seq that actually arrived
 */
public record GapEvent(long marketplaceId, long expectedSeq, long receivedSeq) {
}
