package fm.error;

/**
 * The server refused the request for this caller: a 403. Distinct from {@link AuthenticationException}, which means it did not accept who you are; this means it did, and says no. Retrying will not help.
 */
public final class AuthorizationException extends FlexemarketsException {
    /**
     * With a message and no underlying cause.
     *
     * @param message what went wrong
     */
    public AuthorizationException(String message) {
        super(message);
    }
}
