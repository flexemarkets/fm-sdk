package fm.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One parameter a robot declares.
 *
 * <p>{@code min} and {@code max} are the robot's hard bounds -- what is
 * physically valid, not what suits a study. A manager's range must sit inside
 * them. Null when the robot declares none.
 *
 * @param name         the parameter's name, e.g. {@code penalty}
 * @param label        its display label
 * @param description  what it controls
 * @param type         how it is supplied, e.g. {@code option}
 * @param valueType    the kind of value, e.g. {@code decimal}
 * @param required     whether the robot needs a value
 * @param defaultValue the value the robot runs on unless configured, or null
 * @param unit         its unit, or null
 * @param min          the lowest value the robot accepts, or null
 * @param max          the highest value the robot accepts, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RobotParameter(String name, String label, String description, String type,
                             String valueType, boolean required, String defaultValue,
                             String unit, BigDecimal min, BigDecimal max) {

    /**
     * Built from the wire, where the default is spelled {@code default} and
     * {@code required} may be null for a parameter that is not.
     */
    @JsonCreator
    static RobotParameter fromWire(
            @JsonProperty("name") String name,
            @JsonProperty("label") String label,
            @JsonProperty("description") String description,
            @JsonProperty("type") String type,
            @JsonProperty("valueType") String valueType,
            @JsonProperty("required") Boolean required,
            @JsonProperty("default") String defaultValue,
            @JsonProperty("unit") String unit,
            @JsonProperty("min") BigDecimal min,
            @JsonProperty("max") BigDecimal max) {
        return new RobotParameter(name, label, description, type, valueType,
                required != null && required, defaultValue, unit, min, max);
    }
}
