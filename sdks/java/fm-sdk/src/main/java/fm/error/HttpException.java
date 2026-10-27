package fm.error;

/**
 * A response the SDK has no better name for, carrying its status and body.
 *
 * <p>The fallback. A status with a meaning worth acting on gets its own type --
 * {@link AuthenticationException}, {@link ConflictException} -- and this is
 * what is left, so a caller can read the status rather than parse a message.
 */
public final class HttpException extends FlexemarketsException {
    /**
     * An exception carrying a status the SDK has no better name for.
     *
     * @param statusCode the HTTP status the server answered
     * @param body       the response body, verbatim
     */
    public HttpException(int statusCode, String body) {
        super("HTTP %d: %s".formatted(statusCode, body));
        withResponse(statusCode, null, body);
    }
}
