package fm.error;

/**
 * An order the desk did not send, because fm-server would refuse it.
 *
 * <p>Thrown by {@code Desk.submitLimit} when the session the desk holds is not
 * open, or the holding it holds does not grant the order's side in that market
 * -- the two refusals fm-server answers with 400 {@code SESSION_CLOSED} and
 * {@code ORDER_NOT_ALLOWED}, which {@link #reason()} names. An
 * {@link InvalidArgumentException}, so a caller catching the server's answer
 * catches this too. {@code flexemarkets.desk(id, false)} sends every order
 * instead.
 */
public final class OrderRefusedLocallyException extends InvalidArgumentException {

    private final String _reason;

    /**
     * A refusal the desk made without asking the server.
     *
     * @param reason  the server's name for the refusal: {@code SESSION_CLOSED}
     *                or {@code ORDER_NOT_ALLOWED}
     * @param message what was refused, and why
     */
    public OrderRefusedLocallyException(String reason, String message) {
        super(message);
        this._reason = reason;
    }

    /**
     * The server's name for the refusal.
     *
     * @return {@code SESSION_CLOSED} or {@code ORDER_NOT_ALLOWED}
     */
    public String reason() {
        return _reason;
    }
}
