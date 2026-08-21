package fm;

public sealed class ConflictException extends FlexemarketsException
    permits AccountNameConflictException, PersonHasMarketplaceDataException {

    private final ConflictFailure failure;

    public ConflictException(String message, ConflictFailure failure) {
        super(message);
        this.failure = failure;
    }

    public ConflictFailure failure() { return failure; }
}
