package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Token(
    String requestUrl,
    Person person,
    Account account,
    String token) {
}
