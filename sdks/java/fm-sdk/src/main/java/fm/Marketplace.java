package fm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Marketplace(
    long id,
    String name,
    String description,
    List<Market> markets) {
}
