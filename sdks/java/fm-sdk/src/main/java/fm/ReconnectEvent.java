package fm;

/**
 * Fires when {@link MarketView} reacts to a {@code WsTransportError} —
 * either after the reconnect + resnapshot completes successfully, or
 * after the reconnect attempt has failed and the view is left stale.
 *
 * <p>Subscribe via {@link MarketView#onReconnect(java.util.function.Consumer)}.
 *
 * @param marketplaceId the marketplace the reconnect ran for
 * @param success       {@code true} if the reconnect + REST snapshot
 *                      re-seed both completed; {@code false} if any
 *                      step threw — caller must close() and observe()
 *                      again to recover
 * @param reason        a short description of the failure, or
 *                      {@code null} on success
 */
public record ReconnectEvent(long marketplaceId, boolean success, String reason) {
}
