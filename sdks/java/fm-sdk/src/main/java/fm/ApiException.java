package fm;

/**
 * The call could not be completed: the transport failed, or the response was
 * not something the SDK could read.
 *
 * <p>Distinct from {@link HttpException}, which means the server answered and
 * the answer was an error. This means there was no usable answer at all.
 */
public final class ApiException extends FlexemarketsException {
    /**
     * With a message and no underlying cause.
     *
     * @param message what went wrong
     */
    public ApiException(String message) {
        super(message);
    }

    /**
     * With a message and the failure that produced it.
     *
     * @param message what went wrong
     * @param cause   the failure underneath it
     */
    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
