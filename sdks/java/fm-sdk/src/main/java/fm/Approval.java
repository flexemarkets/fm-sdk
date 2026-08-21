package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The server's answer to an approval request: the account as it now
 * stands, plus what was asked of it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Approval(
    Account account,
    String description,
    Boolean approve) {
}
