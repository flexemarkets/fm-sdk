package fm.internal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Reading the two ways the server spells a moment.
 *
 * <p>It sends both. Audit fields — {@code createdDate}, {@code lastModifiedDate},
 * a session's {@code openDate} — come from {@code LocalDateTime} and arrive
 * bare: {@code "2017-04-11T00:54:35.135"}, no zone, with anywhere between three
 * and nine fractional digits. {@code expiresAt} comes from a real
 * {@code Instant} and arrives as {@code "2026-08-15T18:00:00Z"}.
 *
 * <p><b>A bare timestamp is UTC.</b> That is not a guess: the server writes them
 * from a clock running UTC, and reading one as local time is wrong by the
 * reader's offset — silently, and only for people not in UTC, which is the
 * worst way for a bug to be distributed. The SDK used to hand these out as
 * {@code String} and leave every caller to make that mistake individually;
 * JavaScript's {@code new Date(value)} makes it by default.
 *
 * <p>So both spellings resolve to one {@link Instant} and the ambiguity stops
 * here.
 */
public final class Timestamps {

    private Timestamps() {
    }

    /**
     * The moment a value names, or null if it names none.
     *
     * <p>Null rather than a throw for an unreadable value, for the reason the
     * enums give: one unparseable field should cost the caller that field, not
     * the whole response it arrived in.
     */
    public static Instant parse(String value) {
        if (null == value || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        try {
            // Anything carrying a zone or offset -- "…Z", "…+01:00" -- is
            // already unambiguous.
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException notALocalDateTime) {
                return null;
            }
        }
    }
}
