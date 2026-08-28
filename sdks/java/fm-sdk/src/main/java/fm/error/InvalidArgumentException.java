package fm.error;

/**
 * The server rejected the request as malformed or out of range: a 400. The message carries what it objected to -- a price off the tick, units above the market maximum, a marketplace with no markets.
 */
public final class InvalidArgumentException extends FlexemarketsException {
    /**
     * With a message and no underlying cause.
     *
     * @param message what went wrong
     */
    public InvalidArgumentException(String message) {
        super(message);
    }
}
