package fm;

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
    private final long userId;

    public PersonHasMarketplaceDataException(long userId, String message) {
        super(message, null);
        this.userId = userId;
    }

    public long userId() { return userId; }
}
