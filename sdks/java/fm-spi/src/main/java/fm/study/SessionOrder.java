package fm.study;

/**
 * One order a session took, as the platform kept it. A partially filled
 * order is two orders, the part that traded and the part that did not, as
 * it is on the platform.
 *
 * <p>The platform says an order is done by naming the order that consumed
 * it, which is a trade or a cancel alike; telling them apart means pairing
 * cancels by hand. The host does that once, here, so that no study does it
 * again.
 *
 * @param id           the order's id on the platform
 * @param ownerId      the person's id, as {@link ParticipantHolding#id}
 * @param owner        their email
 * @param symbol       the market's ticker
 * @param side         buy or sell
 * @param price        in cents
 * @param units        how many, always positive
 * @param state        how the order ended
 * @param counterparty the id of the order it traded with, when it traded;
 *                     otherwise null
 */
public record SessionOrder(long id, long ownerId, String owner, String symbol, Side side, long price, long units,
        State state, Long counterparty) {

    /** Which way the order went. */
    public enum Side {
        /** An offer to buy. */
        BUY,
        /** An offer to sell. */
        SELL
    }

    /** How an order ended. */
    public enum State {
        /** Still in the book when the session closed. */
        RESTING,
        /** Traded, with {@link SessionOrder#counterparty}. */
        TRADED,
        /** Withdrawn by its owner, or for them. */
        CANCELLED
    }

    /** An order is for something, one way, in some state, and traded with someone only if it traded. */
    public SessionOrder {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("order " + id + " needs a symbol");
        }
        if (side == null || state == null) {
            throw new IllegalArgumentException("order " + id + " needs a side and a state");
        }
        if (units <= 0) {
            throw new IllegalArgumentException("order " + id + " needs units, not " + units);
        }
        if ((state == State.TRADED) != (counterparty != null)) {
            throw new IllegalArgumentException("order " + id + " has a counterparty only when it traded");
        }
    }

    /**
     * Whether the order was still in the book when the session closed.
     *
     * @return true for {@link State#RESTING}
     */
    public boolean resting() {
        return state == State.RESTING;
    }
}
