package fm;

/**
 * How {@link fm.role.Writing#sendLimit} and {@link fm.role.Writing#sendCancel}
 * send a client's orders, chosen once, when it connects
 * ({@link Flexemarkets#connect(String, String, String, OrderRoute)}).
 *
 * <p>One route for the client's life, so its orders never take two routes and
 * overtake each other on the way (docs/SUBMIT-RATE.md, section 5).
 */
public enum OrderRoute {
    /**
     * The default: each order goes out on the marketplace's order socket at
     * once, as a STOMP SEND, up to 64 unanswered.
     */
    SOCKET,
    /**
     * Orders go as {@code POST .../orders/batch}, one request in flight; those
     * sent while it is out wait, and go together as the next. For a caller that
     * cannot hold a socket open. Needs fm-server 4.19.
     */
    REST
}
