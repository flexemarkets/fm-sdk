package fm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One widget as a study pushes it: content for the participant panel that
 * names its {@code key}.
 *
 * <p>A push to the same target and key replaces the last one, which is how
 * stale content leaves the screen -- there is no separate update.
 *
 * <p>{@code content} is a closed schema the server enforces: {@code text},
 * {@code kv}, {@code table} or {@code log}, each a {@code kind} plus its own
 * fields, never markup. The SDK passes it through as a map rather than
 * modelling the kinds, so the server's validator is the one place the rules
 * live; a push it refuses fails as an {@link fm.error.InvalidArgumentException}
 * carrying the reason.
 *
 * @param target     who it is for; null means the marketplace
 * @param key        the panel's key: letters, digits, {@code _} or {@code -},
 *                   up to 64
 * @param title      an optional heading
 * @param emphasis   {@code normal} (the default when null), {@code strong} or
 *                   {@code warn}
 * @param ttlSeconds blank the panel this long after the push; null for never
 * @param content    the content, as described above
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WidgetPush(
    WidgetTarget target,
    String key,
    String title,
    String emphasis,
    Integer ttlSeconds,
    Map<String, Object> content) {
}
