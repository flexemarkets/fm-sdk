package fm;

/**
 * A response the SDK has no better name for, carrying its status and body.
 *
 * <p>The fallback. A status with a meaning worth acting on gets its own type --
 * {@link AuthenticationException}, {@link ConflictException} -- and this is
 * what is left, so a caller can read the status rather than parse a message.
 */
public final class HttpException extends FlexemarketsException {
    private final int statusCode;
    private final String body;

    public HttpException(int statusCode, String body) {
        super("HTTP %d: %s".formatted(statusCode, body));
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() { return statusCode; }
    public String body() { return body; }
}
