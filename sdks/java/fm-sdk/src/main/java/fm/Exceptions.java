package fm;

public class Exceptions {
    private Exceptions() {}

    public sealed static class FlexemarketsException extends RuntimeException
        permits AuthenticationException, HttpException, ConflictException, ApiException {

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

    public static final class ConflictException extends FlexemarketsException {
        private final Types.ConflictFailure failure;

        public ConflictException(String message, Types.ConflictFailure failure) {
            super(message);
            this.failure = failure;
        }

        public Types.ConflictFailure failure() { return failure; }
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
