package fm.robot.spi;

/**
 * A created, runnable robot instance.
 *
 * <p>Formalizes the {@code start()} / {@code stop()} pair the container invokes
 * today by reflection ({@code application.getClass().getDeclaredMethod(...)}).
 * With a typed interface the host drops the reflection — and the contract is
 * checked at compile time.
 */
public interface Robot {

    /**
     * Begin trading: connect the transport, subscribe to the marketplace,
     * schedule the first tick. Must return promptly — do not block the caller.
     */
    void start();

    /**
     * Stop trading and release resources. Implementations <strong>must close
     * the transport / WebSocket client here</strong>: a stop that leaves the
     * client open leaks the connection inside the host's servlet container and
     * accumulates per start/stop cycle (the known robot WS-lifecycle leak). The
     * host may wait for this to return before reusing the slot.
     */
    void stop();
}
