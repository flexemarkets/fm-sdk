package fm;

/**
 * The root of everything this SDK throws.
 *
 * <p>Unchecked, and sealed. Unchecked because a caller who cannot reach the
 * server usually cannot do anything locally about it either, and a checked
 * exception would put a try/catch around every call to say so. Sealed because
 * the set is closed: a caller can switch over it and the compiler will tell
 * them when it grows.
 *
 * <p>Was a nested class inside a holder called Exceptions until 0.1.0.
 */
public sealed class FlexemarketsException extends RuntimeException
    permits AuthenticationException, HttpException, ConflictException, ApiException,
            PersonHasMarketplaceDataException {

    protected FlexemarketsException(String message) {
        super(message);
    }

    protected FlexemarketsException(String message, Throwable cause) {
        super(message, cause);
    }
}
