package fm;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Person(
    String createdDate,
    String lastModifiedDate,
    Long id,
    Long accountId,
    String firstName,
    String lastName,
    String email,
    String[] roles,
    Boolean accountOwner) {

    public Person {
        id           = Objects.requireNonNullElse(id, 0L);
        accountId    = Objects.requireNonNullElse(accountId, 0L);
        accountOwner = Objects.requireNonNullElse(accountOwner, Boolean.FALSE);
    }
}
