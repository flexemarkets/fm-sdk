package fm.service;

import java.util.ServiceLoader;

/**
 * The entry point an implementation exposes so a host can discover and create
 * a {@link Service} <em>without</em> compiling against the implementation's
 * code.
 *
 * <p>Hosts discover providers via {@link ServiceLoader} — over the classpath,
 * or over a per-jar {@link java.net.URLClassLoader} when implementations are
 * dropped in as external plugin jars:
 *
 * <pre>{@code
 * Map<String, ServiceProvider> catalog = new HashMap<>();
 * for (ServiceProvider provider : ServiceLoader.load(ServiceProvider.class, loader)) {
 *     catalog.put(provider.name(), provider);
 * }
 * }</pre>
 *
 * <p>Each plugin jar declares its implementation in
 * {@code META-INF/services/fm.service.ServiceProvider}.
 */
public interface ServiceProvider {

    /**
     * The contract version this artifact publishes. Bump the minor for
     * additive, backward-compatible changes; the major for anything a host or
     * provider must adapt to. A host accepts any provider whose major matches
     * its own.
     */
    String SPI_VERSION = "1.0";

    /**
     * The unique catalog name identifier for this provider — e.g.
     * {@code "fm-maker"}. Hosts key their catalog on it, so it must be unique
     * across the providers a host loads.
     */
    String name();

    /**
     * Create a runnable {@link Service} bound to the given arguments (a
     * CLI-style {@code -E <endpoint> -C <credential> ...} vector). The returned
     * service does not begin work until {@link Service#start()} is called.
     */
    Service create(String[] arguments);

    /**
     * The contract version this provider was built against; defaults to
     * {@link #SPI_VERSION}. The host compares majors and rejects a mismatch.
     */
    default String spiVersion() {
        return SPI_VERSION;
    }
}
