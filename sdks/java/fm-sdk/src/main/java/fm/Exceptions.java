package fm;

public class Exceptions {
    private Exceptions() {}

    public sealed static class FlexemarketsException extends RuntimeException
        permits AuthenticationException, HttpException, ConflictException, ApiException,
                PersonHasMarketplaceDataException {

        protected FlexemarketsException(String message) {
            super(message);
        }

        protected FlexemarketsException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class AuthenticationException extends FlexemarketsException {
        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class HttpException extends FlexemarketsException {
        private final int statusCode;
        private final String body;

        public HttpException(int statusCode, String body) {
            super("HTTP %d: %s".formatted(statusCode, body));
            this.statusCode = statusCode;
            this.body = body;
        }

        public int statusCode() { return statusCode; }
        public String body() { return body; }
    }

    public sealed static class ConflictException extends FlexemarketsException
        permits AccountNameConflictException {

        private final ConflictFailure failure;

        public ConflictException(String message, ConflictFailure failure) {
            super(message);
            this.failure = failure;
        }

        public ConflictFailure failure() { return failure; }
    }

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
    public static final class AccountNameConflictException extends ConflictException {
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

    /**
     * A user could not be deleted because they still own marketplace data --
     * orders or allotments. Deleting them would orphan it, so the server
     * refuses; the caller has to decide what happens to the data first.
     */
    public static final class PersonHasMarketplaceDataException extends FlexemarketsException {
        private final long userId;

        public PersonHasMarketplaceDataException(long userId, String message) {
            super(message);
            this.userId = userId;
        }

        public long userId() { return userId; }
    }

    public static final class ApiException extends FlexemarketsException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
