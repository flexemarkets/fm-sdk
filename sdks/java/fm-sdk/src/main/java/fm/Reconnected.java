package fm;

/**
 * The stream is back.
 *
 * <p>Emitted once the transport has re-established itself after a
 * {@link StreamDropped}. A consumer's state is stale until it reseeds:
 * whatever happened while the socket was down was not delivered.
 *
 * @param marketplaceId the marketplace whose subscription was restored; one
 *                      connection may hold several
 */
public record Reconnected(long marketplaceId) {}
