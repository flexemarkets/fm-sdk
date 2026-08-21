package fm;

/**
 * A user could not be deleted because they still own marketplace data --
 * orders or allotments. Deleting them would orphan it, so the server
 * refuses; the caller has to decide what happens to the data first.
 */
public final class PersonHasMarketplaceDataException extends FlexemarketsException {
    private final long userId;

    public PersonHasMarketplaceDataException(long userId, String message) {
        super(message);
        this.userId = userId;
    }

    public long userId() { return userId; }
}
