package fm.event;


/**
 * The event stream dropped.
 *
 * <p>Delivered onto a listener's queue when the transport fails. What follows
 * depends on who is reading: {@link fm.Desk} reconnects and reseeds by
 * itself and reports the outcome as a {@link DeskRecovery}, while a caller
 * reading a raw queue sees this and decides.
 *
 * <p>Was {@code StreamDropped}, which named it for a thing it is not. This
 * is a record that arrives on a queue, never something thrown — the name told
 * a reader to {@code catch} what they have to {@code instanceof}. It reads now
 * as one of the three things that can happen to a stream, beside
 * {@link StreamReconnected} and {@link FrameUnreadable}.
 *
 * @param failure what the transport reported
 */
public record StreamDropped(Throwable failure) {}
