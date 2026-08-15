package fm.manifest;

/**
 * Who supplies a parameter's value, and therefore who may see it.
 *
 * <p>This is the whole point of the manifest. Before it, every argument a robot
 * took was equal: whoever launched the robot supplied all of them, so a value
 * the experiment depends on — the risk penalty in an MVO study, say — was set
 * by the participant it was meant to constrain, or matched by hand against a
 * separate {@code fm-capm --penalty} and hoped to stay matched.
 *
 * <p>Ownership makes that structural. A {@link #MANAGER} parameter is fixed for
 * the marketplace before anyone launches anything; a {@link #USER} parameter is
 * the participant's to choose; a {@link #PLATFORM} parameter is neither's to
 * see.
 */
public enum Ownership {
    /**
     * Set by the marketplace manager when configuring the robot, and frozen for
     * every participant. Shown to the participant read-only, so they can see the
     * rules they are operating under without being able to change them.
     */
    MANAGER,

    /**
     * Set by the participant at launch. The only block a launch request may
     * carry values for.
     */
    USER,

    /**
     * Injected by the robot server at spawn — credentials and endpoints. Never
     * accepted from a client and never shown to one: a launch request that
     * tries to set one is rejected rather than ignored, because silently
     * dropping a credential override would hide an attempt to use someone
     * else's.
     */
    PLATFORM
}
