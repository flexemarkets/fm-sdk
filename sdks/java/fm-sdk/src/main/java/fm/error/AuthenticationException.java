package fm.error;

/** The server refused the credentials: a 401, or a token it would not accept. */
public final class AuthenticationException extends FlexemarketsException {
    /**
     * With a message and no underlying cause.
     *
     * @param message what went wrong
     */
    public AuthenticationException(String message) {
        super(message);
    }

    /**
     * With a message and the failure that produced it.
     *
     * @param message what went wrong
     * @param cause   the failure underneath it
     */
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
