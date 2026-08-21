package fm;

/** The server refused the credentials: a 401, or a token it would not accept. */
public final class AuthenticationException extends FlexemarketsException {
    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
