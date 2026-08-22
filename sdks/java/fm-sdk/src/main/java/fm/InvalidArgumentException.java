package fm;

/**
 * The server rejected the request as malformed or out of range: a 400. The message carries what it objected to -- a price off the tick, units above the market maximum, a marketplace with no markets.
 */
public final class InvalidArgumentException extends FlexemarketsException {
    public InvalidArgumentException(String message) {
        super(message);
    }
}
