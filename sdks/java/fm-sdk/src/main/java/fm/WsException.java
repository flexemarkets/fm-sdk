package fm;

/**
 * The stream carried something the SDK could not read.
 *
 * <p>Distinct from {@link WsTransportError}, which means the connection
 * failed. This means it is up and a frame on it could not be parsed, so the
 * stream continues and one message was lost.
 */
public record WsException(String message, Throwable failure) {}
