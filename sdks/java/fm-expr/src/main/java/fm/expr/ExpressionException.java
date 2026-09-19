package fm.expr;

/**
 * An expression that cannot be parsed, or one that cannot be evaluated
 * against the scope it was given.
 *
 * <p>The message is for the manager who wrote the expression -- it names the
 * construct and, for a parse failure, the position -- because that is who
 * can act on it. A participant never sees it: a panel whose expression fails
 * renders as failed, not as a number.
 */
public class ExpressionException extends RuntimeException {

    /** Where in the source a parse failure was found; {@code -1} for an evaluation failure. */
    private final int position;

    /**
     * A parse failure at {@code position}.
     *
     * @param message  what was expected or found
     * @param position the character offset in the expression
     */
    public ExpressionException(String message, int position) {
        super(position >= 0 ? message + " at " + position : message);
        this.position = position;
    }

    /**
     * An evaluation failure.
     *
     * @param message what went wrong
     */
    public ExpressionException(String message) {
        this(message, -1);
    }

    /**
     * Where a parse failure was found.
     *
     * @return the character offset, or {@code -1} for an evaluation failure
     */
    public int position() {
        return position;
    }
}
