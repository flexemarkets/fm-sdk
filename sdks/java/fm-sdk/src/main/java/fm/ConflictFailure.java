package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fm.error.ConflictException;
/**
 * The server's structured account of why it refused a request as conflicting.
 *
 * <p>Carried by {@link ConflictException}. A general handler reads this; a
 * caller that wants the specifics catches the subtype instead.
 *
 * @param status        the HTTP status, as text
 * @param error         the server's short name for the error
 * @param message       its explanation
 * @param path          the route that refused
 * @param suggestedName an alternative account name, where the conflict was a
 *                      name clash; null otherwise
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConflictFailure(
    String status,
    String error,
    String message,
    String path,
    String suggestedName) {
}
