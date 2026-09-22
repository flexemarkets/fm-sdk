package fm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One participant's private study state for one allocation: the row a study
 * uploaded for them, as the server stored it.
 *
 * <p>Private means the participant never receives it. Panels that read it are
 * evaluated on the server and only their rendered values travel, so the fields
 * here -- a group, a side, an induced-value schedule -- reach a manager reading
 * the allocation back and nobody else.
 *
 * <p>Keyed on the allocation rather than a session because it is uploaded
 * before the session that consumes it exists: like an allotment, it is staged
 * and lands when a closed session opens over it. That is also what scopes it,
 * since the next upload stages against the next allocation.
 *
 * @param marketplaceId the marketplace it applies to
 * @param allocationId  the allocation it was staged against
 * @param ownerId       the participant it is for
 * @param ownerEmail    the email the upload keyed it on
 * @param fields        the row's fields by name: numbers, text, or vectors of
 *                      numbers, as the upload declared them
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParticipantState(
    Long marketplaceId,
    Long allocationId,
    Long ownerId,
    String ownerEmail,
    Map<String, Object> fields) {
}
