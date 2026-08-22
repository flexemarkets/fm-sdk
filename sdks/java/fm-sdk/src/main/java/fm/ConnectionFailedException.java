package fm;

/**
 * The server failed to answer: a 5xx. Distinct from {@link ApiException}, which means the exchange did not complete at all. This means it completed and the server reported its own failure, so the request may be worth retrying.
 */
public final class ConnectionFailedException extends FlexemarketsException {
    public ConnectionFailedException(String message) {
        super(message);
    }
}
