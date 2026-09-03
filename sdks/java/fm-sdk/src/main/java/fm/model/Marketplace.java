package fm.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/**
 * A marketplace and the markets in it.
 *
 * @param id          the marketplace's id
 * @param name        its name
 * @param description its description
 * @param markets     the markets it contains; a marketplace cannot be
 *                    created without at least one
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record Marketplace(
    long id,
    String name,
    String description,
    List<Market> markets) {
}
