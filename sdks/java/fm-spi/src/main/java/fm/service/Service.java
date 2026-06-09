package fm.service;

/**
 * A created, runnable service with a host-driven lifecycle.
 *
 * <p>Created by a {@link ServiceProvider}; the host calls {@link #start()} to
 * begin and {@link #stop()} to end. Implementations must release all resources
 * in {@link #stop()} — in particular close any network transport or client —
 * so repeated start/stop cycles don't leak.
 */
public interface Service {

    /** Begin work: connect, subscribe, schedule. Must return promptly. */
    void start();

    /** Stop work and release every resource held (close transports/clients). */
    void stop();
}
