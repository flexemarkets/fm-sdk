package fm;

/**
 * An account name was taken, and the server proposed another.
 *
 * <p>A subtype of {@link ConflictException} rather than a sibling, so a
 * caller that handles conflicts generally still catches this one. The
 * suggestion is worth surfacing rather than retrying blindly: it is the
 * name the account would end up known by.
 *
 * <p>It extended {@link FlexemarketsException} directly until 0.0.14, which
 * made that first paragraph false: {@code catch (ConflictException)} did not
 * catch this, so a caller who handled conflicts generally lost exactly the
 * conflict the SDK went to the trouble of describing. The
 * {@link #failure()} inherited from the supertype reports the same
 * suggestion, so a general handler and a specific one now agree.
 */
public final class AccountNameConflictException extends ConflictException {
    private final String requestedName;
    private final String suggestedName;

    public AccountNameConflictException(String requestedName, String suggestedName) {
        super("Account name '%s' is taken%s".formatted(requestedName,
                suggestedName == null ? "" : "; server suggests '%s'".formatted(suggestedName)),
              new ConflictFailure(null, null, null, null, suggestedName));
        this.requestedName = requestedName;
        this.suggestedName = suggestedName;
    }

    public String requestedName() { return requestedName; }
    public String suggestedName() { return suggestedName; }
}
