package fm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A marketplace's current session, as {@code GET /marketplaces/current-sessions}
 * lists it: which session is current and its state, without the rest of the
 * session.
 *
 * @param marketplaceId the marketplace
 * @param sessionId     its current session
 * @param state         that session's state: {@code OPEN}, {@code PAUSED} or {@code CLOSED}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CurrentSession(long marketplaceId, long sessionId, String state) {
}
