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
 *
 * @param id          stable identifier, and the name the provider registers
 *                    under. A manifest whose id matches no provider on the
 *                    classpath describes a robot that cannot be started.
 * @param name        human-readable name, for a manager's catalogue.
 * @param version     the robot's version, not this schema's.
 * @param description what the robot does, for the same catalogue.
 * @param executable  how a host that starts a process should start it. Ignored
 *                    by hosts that run the robot in their own JVM.
 * @param runtime     what {@code executable} needs, e.g. {@code java}. A host
 *                    without it cannot run this robot as a process.
 * @param parameters  every parameter, split by who owns the value. Absent reads
 *                    as {@link Parameters#empty()}.
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

    /**
     * The three ownership blocks. Absent blocks read as empty, never null.
     *
     * @param manager  set once per marketplace by whoever configures the robot.
     * @param user     supplied by the participant at launch.
     * @param platform supplied by the host, and by nobody else — see
     *                 {@link ParameterSpec#source()}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameters(
            List<ParameterSpec> manager,
            List<ParameterSpec> user,
            List<ParameterSpec> platform) {

        /**
         * Absent blocks become empty lists, so every reader can iterate all
         * three without a null check. A manifest that declares only manager
         * parameters is normal, not malformed.
         */
        public Parameters {
            manager = manager == null ? List.of() : List.copyOf(manager);
            user = user == null ? List.of() : List.copyOf(user);
            platform = platform == null ? List.of() : List.copyOf(platform);
        }

        /**
         * A robot that declares no parameters at all.
         *
         * @return blocks that are all empty.
         */
        public static Parameters empty() {
            return new Parameters(List.of(), List.of(), List.of());
        }
    }

    /** A manifest with no {@code parameters} block declares no parameters. */
    public Manifest {
        parameters = parameters == null ? Parameters.empty() : parameters;
    }

    /**
     * Every declared parameter, regardless of owner.
     *
     * @return manager, then user, then platform, each in declaration order.
     */
    public List<ParameterSpec> all() {
        return Stream.of(parameters.manager(), parameters.user(), parameters.platform())
                .flatMap(List::stream)
                .toList();
    }

    /**
     * The parameters owned by {@code ownership}.
     *
     * @param ownership which block to read.
     * @return that block, in declaration order; empty if it declares none.
     */
    public List<ParameterSpec> owned(Ownership ownership) {
        return switch (ownership) {
            case MANAGER -> parameters.manager();
            case USER -> parameters.user();
            case PLATFORM -> parameters.platform();
        };
    }

    /**
     * The owner of {@code name}, or empty if this manifest does not declare it.
     *
     * <p>Empty is what makes an unknown parameter a refusal rather than a
     * silently dropped value: see {@link CliBuilder#merge}.
     *
     * @param name the parameter's stable identifier.
     * @return who may supply it, or empty if it is not a parameter of this robot.
     */
    public Optional<Ownership> ownerOf(String name) {
        for (Ownership ownership : Ownership.values()) {
            if (owned(ownership).stream().anyMatch(p -> p.name().equals(name))) {
                return Optional.of(ownership);
            }
        }
        return Optional.empty();
    }

    /**
     * One parameter by name, whoever owns it.
     *
     * @param name the parameter's stable identifier.
     * @return the declaration, or empty if this robot has no such parameter.
     */
    public Optional<ParameterSpec> parameter(String name) {
        return all().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /**
     * Every parameter's default, for the parameters that declare one. This is
     * what a manager's configuration form is pre-filled from, so an untouched
     * form saves the same values the robot would have used on its own.
     *
     * @return name to default value, in declaration order; parameters without a
     *         default are absent rather than mapped to null.
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
