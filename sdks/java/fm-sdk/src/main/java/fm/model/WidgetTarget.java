package fm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Who a pushed widget is for: the whole marketplace, or one participant in it.
 *
 * <p>A user-scoped widget outranks a marketplace-scoped one of the same key for
 * that participant, so a study can push a default to everyone and override it
 * for the few who need something else.
 *
 * @param scope  {@code MARKETPLACE} or {@code USER}
 * @param userId the participant, for a {@code USER} widget; null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WidgetTarget(String scope, Long userId) {
}
