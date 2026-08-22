package fm;

/**
 * The server refused the request for this caller: a 403. Distinct from {@link AuthenticationException}, which means it did not accept who you are; this means it did, and says no. Retrying will not help.
 */
public final class AuthorizationException extends FlexemarketsException {
    public AuthorizationException(String message) {
        super(message);
    }
}
