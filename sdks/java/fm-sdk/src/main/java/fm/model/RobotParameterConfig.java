package fm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How a manager has disposed of one robot parameter in one marketplace.
 *
 * <p>Two decisions: the value, always present, and whether participants may
 * move it. A null {@code control} means the value is fixed and the
 * participant's launch form shows it read-only.
 *
 * @param marketplaceId the marketplace it applies to
 * @param robotId       the robot it applies to
 * @param paramName     the parameter
 * @param value         the value the robot runs on, or where a participant's
 *                      control starts
 * @param control       the range participants may choose within, or null
 *                      when fixed
 * @param exposed       whether participants see the parameter at all; true
 *                      exactly when {@code control} is present
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RobotParameterConfig(Long marketplaceId, String robotId, String paramName,
                                   String value, RobotParameterControl control, boolean exposed) {
}
