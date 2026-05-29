package fm;

/**
 * Handle returned by {@code MarketView.on*} listener registrations.
 * Close to unregister. Idempotent — multiple closes are no-ops.
 *
 * <p>Implements {@link AutoCloseable} with a no-throw {@link #close()}
 * so callers can use try-with-resources without exception plumbing:
 *
 * <pre>
 * try (Subscription sub = view.onSessionChange(s -&gt; ...)) {
 *     // do work
 * }
 * </pre>
 */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
