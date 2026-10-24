package fm.study;

import java.util.List;
import java.util.Map;

/**
 * What a session ended with: what each participant held when it closed, and
 * every order it took.
 *
 * <p>Most studies pay from the holdings alone. Some pay from what the orders
 * say, which no holding shows: Credit Bubble's claims are orders that rest
 * in a private market and never trade, and Venture pays each trade by who
 * sold it. Contract 0.2.
 *
 * <p>A study with robots of its own ({@link RobotSpec.Mode#OWN}) is told
 * which of the holdings and orders are theirs: their rows are in the
 * holdings, because they hold what they trade, and a study pays them
 * nothing. Contract 0.3.
 *
 * @param holdings every participant's holding at close, and each of the
 *                 study's own robots'
 * @param orders   every order of the session, in the order they were placed
 * @param robots   the study's own robots' emails, by {@link RobotSpec#key};
 *                 empty for a study without any
 */
public record Close(List<ParticipantHolding> holdings, List<SessionOrder> orders, Map<String, String> robots) {
    /** A session nobody traded in has empty lists, not nulls. */
    public Close {
        holdings = holdings == null ? List.of() : List.copyOf(holdings);
        orders = orders == null ? List.of() : List.copyOf(orders);
        robots = robots == null ? Map.of() : Map.copyOf(robots);
    }

    /**
     * A close with no robots of the study's own: what a 0.2 host gave.
     *
     * @param holdings every participant's holding at close
     * @param orders   every order of the session
     */
    public Close(List<ParticipantHolding> holdings, List<SessionOrder> orders) {
        this(holdings, orders, Map.of());
    }

    /**
     * Whether an email is one of the study's own robots'.
     *
     * @param email an owner's email, as a holding or an order names it
     * @return true when a robot of the study's own has it
     */
    public boolean isRobot(String email) {
        return email != null && robots.values().stream().anyMatch(email::equalsIgnoreCase);
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
