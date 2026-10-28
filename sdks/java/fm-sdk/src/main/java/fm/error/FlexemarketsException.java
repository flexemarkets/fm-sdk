package fm.error;

/**
 * The root of everything this SDK throws.
 *
 * <p>Unchecked, and sealed. Unchecked because a caller who cannot reach the
 * server usually cannot do anything locally about it either, and a checked
 * exception would put a try/catch around every call to say so. Sealed because
 * the set is closed: a caller can switch over it and the compiler will tell
 * them when it grows.
 *
 * <p>Was a nested class inside a holder called Exceptions until 0.1.0.
 */
public sealed class FlexemarketsException extends RuntimeException
    permits AuthenticationException, AuthorizationException, InvalidArgumentException,
            ConnectionFailedException, ConfigurationException, OrderOutcomeUnknownException,
            HttpException, ConflictException, ApiException {

    /** The status the server answered, or null when no server answered. */
    private Integer _statusCode;

    /** The {@code error} the answer named, or null. */
    private String _reason;

    /** The answer's body, verbatim, or null. */
    private String _body;

    /**
     * With a message and no underlying cause.
     *
     * @param message what went wrong
     */
    protected FlexemarketsException(String message) {
        super(message);
    }

    /**
     * With a message and the failure that produced it.
     *
     * @param message what went wrong
     * @param cause   the failure underneath it
     */
    protected FlexemarketsException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Attach the server's answer this exception reports. Set by the SDK where it
     * raises an exception for a response, much as {@link #initCause} is; a
     * caller has no reason to.
     *
     * @param statusCode the HTTP status the server answered
     * @param reason     the {@code error} the body named, or null when it named none
     * @param body       the body, verbatim
     * @return this exception, for {@code throw e.withResponse(...)}
     */
    public FlexemarketsException withResponse(int statusCode, String reason, String body) {
        this._statusCode = statusCode;
        this._reason = reason;
        this._body = body;
        return this;
    }

    /**
     * The status the server answered, when this exception is its answer.
     *
     * @return the HTTP status, or null for an exception the SDK raised itself
     */
    public Integer statusCode() { return _statusCode; }

    /**
     * What the server said went wrong: the body's {@code error} --
     * {@code SESSION_CLOSED}, {@code ORDER_INVALID}, {@code ACCOUNT_TOKEN_EXPIRED}.
     * This is what a caller branches on; the message is for people.
     *
     * @return the error code, or null when there was no answer or it named none
     */
    public String reason() { return _reason; }

    /**
     * The answer's body as the server sent it.
     *
     * @return the body, unparsed, or null for an exception the SDK raised itself
     */
    public String body() { return _body; }
}
