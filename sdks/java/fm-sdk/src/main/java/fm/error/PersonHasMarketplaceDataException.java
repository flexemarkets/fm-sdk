package fm.error;

/**
 * A user could not be deleted because they still own marketplace data --
 * orders or allotments. Deleting them would orphan it, so the server
 * refuses; the caller has to decide what happens to the data first.
 *
 * <p>A {@link ConflictException}, because that is what the server answers: a
 * 409. It extended {@link FlexemarketsException} directly until 0.1.0, which
 * left the three SDKs disagreeing -- Python and TypeScript both make it a
 * conflict -- and meant a caller handling conflicts generally missed this one.
 */
public final class PersonHasMarketplaceDataException extends ConflictException {
    /** The user who could not be deleted. */
    private final long _userId;

    /**
     * A refusal naming the user whose data blocked the delete.
     *
     * @param userId  the user who could not be deleted
     * @param message the server's explanation
     */
    public PersonHasMarketplaceDataException(long userId, String message) {
        super(message, null);
        this._userId = userId;
    }

    /**
     * Who the refusal was about.
     *
     * @return the user that still owns marketplace data
     */
    public long userId() { return _userId; }
}
