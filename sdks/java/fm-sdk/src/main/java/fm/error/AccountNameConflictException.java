package fm.error;

import fm.model.Account;
import fm.model.ConflictFailure;


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
    /** The name that was asked for. */
    private final String _requestedName;

    /** The server's proposed alternative, or null. */
    private final String _suggestedName;

    /**
     * A refusal naming both the name asked for and the one proposed instead.
     *
     * @param requestedName the name that was asked for
     * @param suggestedName the server's proposed alternative, or null if it
     *                      offered none
     */
    public AccountNameConflictException(String requestedName, String suggestedName) {
        super("Account name '%s' is taken%s".formatted(requestedName,
                suggestedName == null ? "" : "; server suggests '%s'".formatted(suggestedName)),
              new ConflictFailure(null, null, null, null, suggestedName));
        this._requestedName = requestedName;
        this._suggestedName = suggestedName;
    }

    /**
     * The name that was asked for and refused.
     *
     * @return the requested account name
     */
    public String requestedName() { return _requestedName; }

    /**
     * The name the account would end up known by if the suggestion is taken.
     *
     * @return the server's proposed alternative, or null if it offered none
     */
    public String suggestedName() { return _suggestedName; }
}
