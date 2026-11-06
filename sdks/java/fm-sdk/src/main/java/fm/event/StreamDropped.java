package fm.event;


/**
 * The event stream dropped.
 *
 * <p>Delivered onto a listener's queue when the transport fails, and to
 * {@link fm.Desk#onStreamDropped} handlers. {@code permanent} says what happens
 * next. False: the listener is restoring it, and a {@link StreamReconnected}
 * follows when it has, which a {@link fm.Desk} answers by reseeding and reporting
 * a {@link DeskRecovery}. True: it has given up, its token refused twice, and
 * nothing more will arrive.
 *
 * <p>Was {@code StreamDropped}, which named it for a thing it is not. This
 * is a record that arrives on a queue, never something thrown — the name told
 * a reader to {@code catch} what they have to {@code instanceof}. It reads now
 * as one of the three things that can happen to a stream, beside
 * {@link StreamReconnected} and {@link FrameUnreadable}.
 *
 * @param failure   what the transport reported
 * @param permanent whether the stream has given up rather than being restored
 */
public record StreamDropped(Throwable failure, boolean permanent) {}
