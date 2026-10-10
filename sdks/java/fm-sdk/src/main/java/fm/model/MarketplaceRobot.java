package fm.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One robot the marketplace's robot host offers, as the marketplace sees it.
 *
 * <p>{@code configuration} holds only the parameters a manager has disposed
 * of. A parameter with no row runs on the manifest's default and is not shown
 * to participants -- so "not configured" and "fixed at the default" read the
 * same at launch, and differ only in that nobody chose the second.
 *
 * @param manifest      what the robot is and which parameters it takes
 * @param enabled       whether participants of this marketplace may launch it
 * @param configuration the manager's disposition of each configured parameter
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketplaceRobot(RobotManifest manifest, boolean enabled,
                               List<RobotParameterConfig> configuration) {

    /** Built from the wire; an absent configuration is none, not null. */
    @JsonCreator
    static MarketplaceRobot fromWire(
            @JsonProperty("manifest") RobotManifest manifest,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("configuration") List<RobotParameterConfig> configuration) {
        return new MarketplaceRobot(manifest, enabled,
                configuration == null ? List.of() : List.copyOf(configuration));
    }
}
