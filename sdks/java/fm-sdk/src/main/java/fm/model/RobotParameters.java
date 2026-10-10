package fm.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A robot's parameters, grouped by who may set them.
 *
 * <p>A {@code platform} parameter -- a credential, an endpoint -- is never a
 * manager's to configure, and the server refuses one.
 *
 * @param manager  parameters a manager may fix or expose
 * @param user     parameters a manager may also expose to participants
 * @param platform parameters the platform supplies
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RobotParameters(List<RobotParameter> manager, List<RobotParameter> user,
                              List<RobotParameter> platform) {

    /** Built from the wire; a group the manifest leaves out is empty, not null. */
    @JsonCreator
    static RobotParameters fromWire(
            @JsonProperty("manager") List<RobotParameter> manager,
            @JsonProperty("user") List<RobotParameter> user,
            @JsonProperty("platform") List<RobotParameter> platform) {
        return new RobotParameters(_list(manager), _list(user), _list(platform));
    }

    private static List<RobotParameter> _list(List<RobotParameter> parameters) {
        return parameters == null ? List.of() : List.copyOf(parameters);
    }
}
