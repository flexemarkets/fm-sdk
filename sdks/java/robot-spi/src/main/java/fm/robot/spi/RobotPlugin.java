package fm.robot.spi;

import java.util.ServiceLoader;

/**
 * The entry point a robot exposes so a host can discover and create it
 * <em>without</em> compiling against the robot's code.
 *
 * <p>Hosts (the robot container, fm-server's in-process supervisor) discover
 * implementations via {@link ServiceLoader} — over the classpath today, or
 * over a per-jar {@link java.net.URLClassLoader} when robots are dropped in as
 * external plugin jars:
 *
 * <pre>{@code
 * Map<String, RobotPlugin> catalog = new HashMap<>();
 * for (RobotPlugin plugin : ServiceLoader.load(RobotPlugin.class, loader)) {
 *     catalog.put(plugin.type(), plugin);
 * }
 * }</pre>
 *
 * <p>Each robot (plugin) jar declares its implementation in
 * {@code META-INF/services/fm.robot.spi.RobotPlugin}.
 *
 * <p>This replaces today's hard-coded {@code switch(type)} catalog in the
 * container's {@code Agent._create(...)}.
 */
public interface RobotPlugin {

    /**
     * Stable catalog id — e.g. {@code "fm-maker"}, {@code "fm-taker-mvo"}.
     * Matches the agent type the container already keys on.
     */
    String type();

    /**
     * Create a runnable robot bound to the given CLI-style arguments — the
     * same {@code -E <endpoint> -C <credential> ...} vector the robots parse
     * today. Mirrors the existing {@code Application.create(String[])} factory,
     * so an adapter is a one-line delegation.
     *
     * @return a started-on-demand {@link Robot}; implementations must not begin
     *         trading until {@link Robot#start()} is called.
     */
    Robot create(String[] arguments);

    /**
     * The SPI major.minor this plugin was built against. The host rejects a
     * plugin whose major differs from its own and logs a clear message; minor
     * skew is allowed (additive changes only). Defaults to the version this
     * jar was compiled with.
     */
    default String spiVersion() {
        return SpiVersion.CURRENT;
    }
}
