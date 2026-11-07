package fm.event;

import fm.error.FlexemarketsException;


/**
 * An order sent over the socket was refused, or its acceptance could not be
 * read, when {@code error} is the {@link fm.error.ApiException} REST throws for an
 * answer it cannot read and the order may have been placed.
 *
 * <p>{@code error} is what REST throws for the same refusal, built from the
 * same failure body, so a caller handles a refused order one way whichever
 * route it took.
 *
 * @param submissionId the submission this answers, or null if the frame did not say
 * @param error        the refusal, as REST would have thrown it
 */
public record OrderRejected(String submissionId, FlexemarketsException error) {
}
