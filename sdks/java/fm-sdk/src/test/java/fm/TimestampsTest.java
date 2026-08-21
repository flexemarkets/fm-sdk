package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The server spells a moment two ways, and one of them is a trap.
 *
 * <p>Audit fields arrive bare -- {@code "2017-04-11T00:54:35.135"} -- because
 * they come from a LocalDateTime. {@code expiresAt} arrives as
 * {@code "2026-08-15T18:00:00Z"} because it comes from an Instant. Both are
 * real, sampled from production.
 *
 * <p>A bare timestamp is UTC: the server's clock runs UTC. Reading one as local
 * time is wrong by the reader's offset, silently, and only for people not in
 * UTC -- so it works on the server, works in CI, and is wrong on a laptop in
 * Denver. Handing these out as String, as the SDK used to, left every caller to
 * make that mistake individually.
 */
class TimestampsTest {

    /** Sampled from api.flexemarkets.com: three fractional digits, no zone. */
    @Test
    void aBareTimestampIsReadAsUtc() {
        assertThat(Timestamps.parse("2017-04-11T00:54:35.135"))
                .isEqualTo(Instant.parse("2017-04-11T00:54:35.135Z"));
    }

    /** The same field elsewhere in the same response carries six. */
    @Test
    void fractionalPrecisionVaries() {
        assertThat(Timestamps.parse("2026-05-16T07:44:50.804552"))
                .isEqualTo(Instant.parse("2026-05-16T07:44:50.804552Z"));
        assertThat(Timestamps.parse("2026-05-16T07:44:50"))
                .isEqualTo(Instant.parse("2026-05-16T07:44:50Z"));
    }

    /** expiresAt comes from an Instant and says so. */
    @Test
    void aZonedTimestampIsTakenAsGiven() {
        assertThat(Timestamps.parse("2026-08-15T18:00:00Z"))
                .isEqualTo(Instant.parse("2026-08-15T18:00:00Z"));
    }

    /** An offset is unambiguous too, and is not shifted again. */
    @Test
    void anOffsetIsHonoured() {
        assertThat(Timestamps.parse("2026-08-15T19:00:00+01:00"))
                .isEqualTo(Instant.parse("2026-08-15T18:00:00Z"));
    }

    /**
     * One unreadable field should cost the caller that field, not the response
     * it arrived in -- the same rule the enums follow.
     */
    @Test
    void anUnreadableValueIsNullRatherThanAThrow() {
        assertThat(Timestamps.parse("not a date")).isNull();
        assertThat(Timestamps.parse("")).isNull();
        assertThat(Timestamps.parse("   ")).isNull();
        assertThat(Timestamps.parse(null)).isNull();
    }
}
