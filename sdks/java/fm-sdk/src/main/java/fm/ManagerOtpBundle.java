package fm;

import java.util.List;

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
public record ManagerOtpBundle(String expiresAt, List<Entry> otps) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(long userId, String email, String otp) {
    }
}
