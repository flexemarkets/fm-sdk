package fm;

/**
 * The stream is back.
 *
 * <p>Emitted once the transport has re-established itself after a
 * {@link WsTransportError}. A consumer's state is stale until it reseeds:
 * whatever happened while the socket was down was not delivered.
 */
public record Reconnected(long marketplaceId) {}
