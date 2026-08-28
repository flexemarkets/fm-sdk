package fm;

/**
 * The server refused a request because it conflicts with what already exists.
 *
 * <p>A 409. Sealed over the two cases the server describes precisely -- a taken
 * account name, and a user who still owns marketplace data -- so a caller may
 * either catch this and read {@link #failure()}, or catch the subtype and read
 * the specifics. Both were siblings of this class rather than subtypes until
 * 0.1.0, which meant a general handler caught neither.
 */
public sealed class ConflictException extends FlexemarketsException
    permits AccountNameConflictException, PersonHasMarketplaceDataException {

    /** The server's structured account of the conflict, or null. */
    private final ConflictFailure _failure;

    /**
     * A conflict, with whatever detail the server gave.
     *
     * @param message what went wrong
     * @param failure the server's structured account of it, or null if it gave
     *                none
     */
    public ConflictException(String message, ConflictFailure failure) {
        super(message);
        this._failure = failure;
    }

    /**
     * The server's own description of the conflict.
     *
     * @return the failure detail, or null if the server gave none
     */
    public ConflictFailure failure() { return _failure; }
}
