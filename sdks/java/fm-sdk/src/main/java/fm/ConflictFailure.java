package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConflictFailure(
    String status,
    String error,
    String message,
    String path,
    String suggestedName) {
}
