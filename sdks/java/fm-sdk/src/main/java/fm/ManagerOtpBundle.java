package fm;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One-time passcodes a manager mints on behalf of their users.
 *
 * <p>Credentials, and short-lived: {@code expiresAt} is when the whole
 * bundle stops working, not a per-entry deadline. Handing these out is how
 * a classroom gets its students logged in without issuing passwords, which
 * is also why nothing here should be logged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManagerOtpBundle(Instant expiresAt, List<Entry> otps) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(long userId, String email, String otp) {
    }

    /**
     * Built from the wire, where a timestamp is a string in one of two shapes.
     * See {@link Timestamps}; a @JsonCreator rather than a @JsonDeserialize so
     * the record does not depend on one Jackson major.
     */
    @JsonCreator
    static ManagerOtpBundle fromWire(
            @JsonProperty("expiresAt") String expiresAt,
            @JsonProperty("otps") List<Entry> otps) {
        return new ManagerOtpBundle(Timestamps.parse(expiresAt), otps);
    }

}
