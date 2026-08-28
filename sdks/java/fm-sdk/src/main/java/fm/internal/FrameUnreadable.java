package fm.internal;

/**
 * The stream carried something the SDK could not read.
 *
 * <p>Distinct from {@link StreamDropped}, which means the connection failed.
 * This means it is up and a frame on it could not be parsed, so the stream
 * continues and one message was lost.
 *
 * <p>Was {@code FrameUnreadable}, which named it for a thing it is not: a record
 * delivered on a queue, never thrown.
 *
 * @param message what could not be read
 * @param failure the parse failure underneath it
 */
public record FrameUnreadable(String message, Throwable failure) {}
