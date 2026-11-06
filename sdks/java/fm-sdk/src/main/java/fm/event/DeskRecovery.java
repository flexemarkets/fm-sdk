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
 * <p>Fires after a reconnect, whether its re-seed worked or not. After a
 * sequence gap, when its re-seed fails and so when a later one ends that; a gap
 * that re-seeds first time is only a {@link GapEvent}.
 *
 * <p>Subscribe via {@link fm.Desk#onRecovery(java.util.function.Consumer)}.
 *
 * @param marketplaceId the marketplace the recovery ran for
 * @param success       {@code true} if the REST snapshot re-seed completed;
 *                      {@code false} if it threw, and the books are stale until
 *                      a later one reports {@code true}: the desk goes on trying
 * @param reason        a short description of the failure, or
 *                      {@code null} on success
 */
public record DeskRecovery(long marketplaceId, boolean success, String reason) {
}
