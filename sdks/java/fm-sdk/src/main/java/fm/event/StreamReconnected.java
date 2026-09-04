package fm.event;

/**
 * The stream is back.
 *
 * <p>Emitted once the transport has re-established itself after a
 * {@link StreamDropped}. A consumer's state is stale until it reseeds:
 * whatever happened while the socket was down was not delivered.
 *
 * <p>This is the transport saying so, and it arrives on the queue passed to
 * {@code listen}. It cannot fail -- a socket is either back or it is not. The
 * desk-level counterpart is {@link DeskRecovery}, which covers the reconnect
 * <em>and</em> the REST reseed the desk does on top of it, and which therefore
 * can report failure.
 *
 * @param marketplaceId the marketplace whose subscription was restored; one
 *                      connection may hold several
 */
public record StreamReconnected(long marketplaceId) {}
