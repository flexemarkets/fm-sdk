package fm.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a manifest plus the values from all three owners into the argument list
 * a robot is actually launched with.
 *
 * <p>This is where ownership stops being documentation. The manager's values and
 * the participant's values arrive from different requests, at different times,
 * with different authority, and this is the one place they are combined — so
 * "the participant cannot change the risk penalty" is enforced by the merge
 * rather than by the form that happens to be rendered.
 */
public final class CliBuilder {

    private CliBuilder() {}

    /** Thrown when supplied values do not satisfy the manifest. */
    public static class InvalidLaunchException extends RuntimeException {

        /**
         * Refuse a launch, saying why.
         *
         * @param message what was wrong, named specifically enough to fix:
         *                which parameter, and what was expected of it.
         */
        public InvalidLaunchException(String message) {
            super(message);
        }
    }

    /**
     * Merge the three sources into one map, refusing any attempt to supply a
     * value the caller does not own.
     *
     * @param manifest the robot's declaration of what it takes
     * @param manager  values the manager configured for this marketplace
     * @param user     values the participant supplied at launch
     * @param platform values the server injects
     * @return every parameter with a value: the declared defaults, overlaid by
     *         each owner's own.
     * @throws InvalidLaunchException if a value is supplied by someone who does
     *         not own it, is not a parameter of this robot, does not satisfy
     *         its declared type, or is required and missing.
     */
    public static Map<String, String> merge(
            Manifest manifest,
            Map<String, String> manager,
            Map<String, String> user,
            Map<String, String> platform) {

        Map<String, String> merged = new LinkedHashMap<>(manifest.defaults());

        accept(manifest, merged, manager, Ownership.MANAGER);
        accept(manifest, merged, user, Ownership.USER);
        accept(manifest, merged, platform, Ownership.PLATFORM);

        for (ParameterSpec spec : manifest.all()) {
            if (spec.required() && !merged.containsKey(spec.name())) {
                throw new InvalidLaunchException(
                        "'" + spec.name() + "' is required and was not supplied");
            }
        }

        return merged;
    }

    /**
     * Whether {@code by} may supply a value for a parameter the manifest
     * declares under {@code owner}.
     *
     * <p>Only {@link Ownership#PLATFORM} is absolute. A credential is never
     * eligible to be anyone else's, and that is the robot's call rather than the
     * manager's, so platform parameters may be supplied by the platform and by
     * nothing else, in either direction.
     *
     * <p>The manager/user split is not absolute, and reading it that way was a
     * defect. PLATFORM-ROBOTS.md §5.1 is explicit: the blocks state
     * <em>eligibility</em> — may this ever face a participant — decided by the
     * robot at build time, while <em>disposition</em> is the manager's, per
     * marketplace. The blocks are a suggested default split, "not the
     * enforcement point": an earlier draft made ownership static in the manifest
     * and could not express "the manager decides which controls their
     * participants get".
     *
     * <p>This built that draft anyway, and the effect was worse than a strict
     * rule. A manager who fixes a user-eligible parameter — which is what every
     * robot looks like before anyone exposes anything — produced a launch that
     * fm-server accepted, resolved correctly, and then could not build a command
     * line for. Robots with any user block could be configured and never
     * started, and the failure arrived as a 500 at the moment of launch.
     *
     * <p>So: a participant may never supply a manager parameter, which is the
     * guarantee worth keeping and the reason this rule is in the contract at
     * all. A manager supplying a user-eligible one is ordinary.
     *
     * @param owner the block the manifest declares the parameter in.
     * @param by    who is supplying the value.
     * @return whether the value is admissible.
     */
    static boolean maySupply(Ownership owner, Ownership by) {
        if (Ownership.PLATFORM == owner || Ownership.PLATFORM == by) {
            return owner == by;
        }
        return Ownership.MANAGER == by || owner == by;
    }

    private static void accept(
            Manifest manifest,
            Map<String, String> merged,
            Map<String, String> supplied,
            Ownership by) {

        if (supplied == null) {
            return;
        }
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            String name = entry.getKey();
            Ownership owner = manifest.ownerOf(name).orElseThrow(() ->
                    new InvalidLaunchException(
                            "'" + name + "' is not a parameter of " + manifest.id()));

            // Refused, not dropped. Silently ignoring a participant's attempt to
            // set a manager parameter would leave them believing the value took
            // effect, and would hide an attempt to override a credential.
            if (!maySupply(owner, by)) {
                throw new InvalidLaunchException(
                        "'" + name + "' is owned by " + owner + " and cannot be set by " + by);
            }

            ParameterSpec spec = manifest.parameter(name).orElseThrow();
            String value = entry.getValue();
            if (spec.valueType() != null && value != null && !spec.valueType().accepts(value)) {
                throw new InvalidLaunchException(
                        "'" + name + "' expects " + spec.valueType().json() + ", got: " + value);
            }
            merged.put(name, value);
        }
    }

    /**
     * Build the argument list.
     *
     * <p>Options first, then positionals in declaration order, then repeating
     * pairs — which must come last, since a trailing {@code (SYMBOL SPREAD)...}
     * would otherwise swallow the arguments that follow it.
     *
     * @param manifest the robot's declaration of what it takes
     * @param values   already merged and checked, as {@link #merge} returns
     * @return the argument vector, in the order above. Parameters with no value
     *         contribute nothing rather than an empty argument.
     * @throws InvalidLaunchException if a repeating parameter's tokens do not
     *         divide into whole groups.
     */
    public static List<String> arguments(Manifest manifest, Map<String, String> values) {
        List<String> options = new ArrayList<>();
        List<String> positionals = new ArrayList<>();
        List<String> pairs = new ArrayList<>();

        for (ParameterSpec spec : manifest.all()) {
            String value = values.get(spec.name());
            if (value == null || value.isBlank()) {
                continue;
            }
            switch (spec.type() == null ? ParameterType.OPTION : spec.type()) {
                case OPTION -> {
                    options.add(spec.cli());
                    options.add(value);
                }
                case POSITIONAL -> positionals.add(value);
                case POSITIONAL_PAIRS, POSITIONAL_TRIPLES -> {
                    // Stored as one flat whitespace-separated string: the robots
                    // take these as bare arguments, so the grouping is
                    // positional on the command line too.
                    String[] tokens = value.trim().split("\\s+");
                    int size = spec.type().groupSize();
                    if (tokens.length % size != 0) {
                        throw new InvalidLaunchException(
                                "'" + spec.name() + "' takes groups of " + size
                                        + " but got " + tokens.length + " values: " + value);
                    }
                    for (String token : tokens) {
                        if (!token.isEmpty()) {
                            pairs.add(token);
                        }
                    }
                }
            }
        }

        List<String> arguments = new ArrayList<>(options);
        arguments.addAll(positionals);
        arguments.addAll(pairs);
        return arguments;
    }

    /**
     * Merge and build in one step. This is what a host calls.
     *
     * @param manifest the robot's declaration of what it takes
     * @param manager  values the manager configured for this marketplace
     * @param user     values the participant supplied at launch
     * @param platform values the server injects
     * @return the argument vector to start the robot with.
     * @throws InvalidLaunchException for anything either step refuses.
     */
    public static List<String> arguments(
            Manifest manifest,
            Map<String, String> manager,
            Map<String, String> user,
            Map<String, String> platform) {
        return arguments(manifest, merge(manifest, manager, user, platform));
    }
}
