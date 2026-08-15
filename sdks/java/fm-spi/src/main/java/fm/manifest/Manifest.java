package fm.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A robot's declaration of itself: what it is called, and every parameter it
 * takes, split by who owns the value.
 *
 * <p>Shipped inside each robot's JAR at {@code META-INF/fm-manifest.json}, so a
 * robot describes its own command line and the executor needs no compile-time
 * knowledge of it. Today {@code Agent._create()} is a {@code switch} over four
 * hardcoded types; a manifest is what lets that become a directory scan.
 *
 * <p>The split into manager/user/platform is the part that matters for
 * operators. See {@link Ownership}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(
        String id,
        String name,
        String version,
        String description,
        String executable,
        String runtime,
        Parameters parameters) {

    /** The three ownership blocks. Absent blocks read as empty, never null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameters(
            List<ParameterSpec> manager,
            List<ParameterSpec> user,
            List<ParameterSpec> platform) {

        public Parameters {
            manager = manager == null ? List.of() : List.copyOf(manager);
            user = user == null ? List.of() : List.copyOf(user);
            platform = platform == null ? List.of() : List.copyOf(platform);
        }

        public static Parameters empty() {
            return new Parameters(List.of(), List.of(), List.of());
        }
    }

    public Manifest {
        parameters = parameters == null ? Parameters.empty() : parameters;
    }

    /** Every declared parameter, regardless of owner. */
    public List<ParameterSpec> all() {
        return Stream.of(parameters.manager(), parameters.user(), parameters.platform())
                .flatMap(List::stream)
                .toList();
    }

    /** The parameters owned by {@code ownership}. */
    public List<ParameterSpec> owned(Ownership ownership) {
        return switch (ownership) {
            case MANAGER -> parameters.manager();
            case USER -> parameters.user();
            case PLATFORM -> parameters.platform();
        };
    }

    /** The owner of {@code name}, or empty if this manifest does not declare it. */
    public Optional<Ownership> ownerOf(String name) {
        for (Ownership ownership : Ownership.values()) {
            if (owned(ownership).stream().anyMatch(p -> p.name().equals(name))) {
                return Optional.of(ownership);
            }
        }
        return Optional.empty();
    }

    public Optional<ParameterSpec> parameter(String name) {
        return all().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /**
     * Every parameter's default, for the parameters that declare one. This is
     * what a manager's configuration form is pre-filled from, so an untouched
     * form saves the same values the robot would have used on its own.
     */
    public Map<String, String> defaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (ParameterSpec spec : all()) {
            if (spec.defaultValue() != null) {
                defaults.put(spec.name(), spec.defaultValue());
            }
        }
        return defaults;
    }
}
