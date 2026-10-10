package fm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What a robot declares about itself: its id, version and parameters.
 *
 * @param id          the robot's id, e.g. {@code fm-maker-mvo}
 * @param version     the robot's version
 * @param name        its display name
 * @param description what it does
 * @param parameters  the parameters it takes, by who may set them
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RobotManifest(String id, String version, String name, String description,
                            RobotParameters parameters) {
}
