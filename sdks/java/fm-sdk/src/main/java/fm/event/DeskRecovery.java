package fm.event;

/**
 * How a desk's recovery ended.
 *
 * <p>A desk does more than reconnect: it re-seeds the books over REST once the
 * transport is back, and either step can throw. This reports the outcome of the
 * whole episode, which is why it carries {@code success} where the transport's
 * own {@link StreamReconnected} carries nothing -- a socket is either back or it
 * is not, but a recovery can complete and still leave the desk stale.
 *
 * <p>Subscribe via {@link fm.Desk#onRecovery(java.util.function.Consumer)}.
 *
 * @param marketplaceId the marketplace the reconnect ran for
 * @param success       {@code true} if the reconnect + REST snapshot
 *                      re-seed both completed; {@code false} if any
 *                      step threw — caller must close() and desk()
 *                      again to recover
 * @param reason        a short description of the failure, or
 *                      {@code null} on success
 */
public record DeskRecovery(long marketplaceId, boolean success, String reason) {
}
