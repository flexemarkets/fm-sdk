package fm.error;

/**
 * An order was sent and no answer to it can now arrive.
 *
 * <p>Not a refusal: fm-server may well have placed it. Thrown for an order sent
 * over the socket ({@link fm.role.Writing#sendLimit}) that was still unanswered
 * when its stream closed. Placing it again under a new submission risks holding
 * the position twice; read the book, or the orders, to learn whether it stands.
 */
public final class OrderOutcomeUnknownException extends FlexemarketsException {

    /** The id the order was sent under. */
    private final String _submissionId;

    /**
     * An order whose answer can no longer come.
     *
     * @param message      what happened
     * @param submissionId the id it was sent under, which the order carries if it was placed
     */
    public OrderOutcomeUnknownException(String message, String submissionId) {
        super(message);
        this._submissionId = submissionId;
    }

    /**
     * The id the order was sent under.
     *
     * @return the submission id, which the order carries if it was placed
     */
    public String submissionId() {
        return _submissionId;
    }
}
