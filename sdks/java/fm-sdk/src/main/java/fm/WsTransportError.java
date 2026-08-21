package fm;

/**
 * The event stream dropped.
 *
 * <p>Delivered onto a listener's queue when the transport fails. What follows
 * depends on who is reading: {@link MarketView} reconnects and reseeds by
 * itself and reports the outcome as a {@link ReconnectEvent}, while a caller
 * reading a raw queue sees this and decides.
 *
 * <p>Was nested inside the STOMP client as {@code Events.WsTransportError},
 * which is why that class looked like published API — the transport is not,
 * and these three event records were the only part of it anyone imported.
 */
public record WsTransportError(Throwable failure) {}
