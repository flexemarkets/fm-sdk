package fm;

import java.io.IOException;

/**
 * Supplies a {@link Flexemarkets} for endpoint forms this SDK cannot reach on
 * its own.
 *
 * <p>The SDK talks HTTP. A host that runs robots inside its own JVM does not
 * want it to: crossing a socket to reach handlers in the same process is pure
 * cost, and it gives up the ordering the host already guarantees internally.
 * Such a host implements this, registers it through {@code ServiceLoader}, and
 * hands its robots an endpoint only it recognises.
 *
 * <p>The robot is unaware. It is still built by {@code ServiceProvider.create}
 * from {@code -E} and {@code -C}, still calls {@link Flexemarkets#connect}, and
 * still cannot tell what it got back — which is the point. An in-process robot
 * that had to be written differently from a networked one would be a second
 * robot to maintain.
 *
 * <p>Deliberately <em>not</em> part of {@code fm-spi}. The SPI is the contract
 * between a host and a robot; this is the contract between a host and this SDK.
 * A robot never implements it and never sees it.
 *
 * <h2>Registering one</h2>
 * <pre>{@code
 * // META-INF/services/fm.FlexemarketsProvider
 * com.example.LoopbackProvider
 * }</pre>
 *
 * @see Flexemarkets#connect(String, String, String)
 */
public interface FlexemarketsProvider {

    /**
     * Whether this provider serves {@code endpoint}.
     *
     * <p>Should be cheap and total: it is asked about every endpoint, including
     * ordinary {@code http://} ones meant for the SDK itself. Recognise a form
     * of your own — a scheme, a prefix — rather than trying to claim anything
     * plausible, because claiming an endpoint you cannot serve turns a working
     * HTTP connection into a failure.
     */
    boolean handles(String endpoint);

    /**
     * Build a connection for an endpoint this provider {@link #handles}.
     *
     * <p>Called only after {@code handles} returned true. The result is owned by
     * the caller and will be closed by it, exactly as an HTTP connection is.
     */
    Flexemarkets connect(String credential, String endpoint, String clientDescription)
            throws IOException;
}
