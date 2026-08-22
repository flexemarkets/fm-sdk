package fm;

/**
 * The SDK could not be configured to make the call at all.
 *
 * <p>A credential that is neither a file nor a token, an endpoint that names
 * nothing. Local: nothing was sent, so no status describes it, and the fix is
 * in the caller's configuration rather than on the server.
 */
public final class ConfigurationException extends FlexemarketsException {
    public ConfigurationException(String message) {
        super(message);
    }
}
