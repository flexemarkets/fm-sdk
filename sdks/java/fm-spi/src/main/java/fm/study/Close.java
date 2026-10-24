package fm.study;

import java.util.List;

/**
 * What a session ended with: what each participant held when it closed, and
 * every order it took.
 *
 * <p>Most studies pay from the holdings alone. Some pay from what the orders
 * say, which no holding shows: Credit Bubble's claims are orders that rest
 * in a private market and never trade, and Venture pays each trade by who
 * sold it. Contract 0.2.
 *
 * @param holdings every participant's holding at close
 * @param orders   every order of the session, in the order they were placed
 */
public record Close(List<ParticipantHolding> holdings, List<SessionOrder> orders) {
    /** A session nobody traded in has empty lists, not nulls. */
    public Close {
        holdings = holdings == null ? List.of() : List.copyOf(holdings);
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    /**
     * A close known only by its holdings: what a 0.1 host had to give.
     *
     * @param holdings every participant's holding at close
     * @return the close, with no orders
     */
    public static Close of(List<ParticipantHolding> holdings) {
        return new Close(holdings, List.of());
    }
}
