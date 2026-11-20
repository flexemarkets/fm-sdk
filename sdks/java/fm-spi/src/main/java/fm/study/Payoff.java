package fm.study;

import java.util.List;

/**
 * What a participant is paid at the end of a rotation: the cash they take
 * into the settlement session. Positions are zeroed and trading is locked
 * there, so every study's payoff has one shape.
 *
 * <p>{@code lines} say how the cash was reached, for the participant's result
 * card: "Cash at close 12.00", "STK · 4 units × 3.00 12.00". A study that
 * gives none leaves the card showing the payoff alone, as before contract 0.6.
 *
 * @param id    the person's id on the platform
 * @param email their email
 * @param name  the allotment name to carry into the settlement session,
 *              so they recognise their row
 * @param cash  the payoff, in cents; may be negative
 * @param lines how the payoff was reached, in the order the card shows them;
 *              empty when the study says nothing more
 */
public record Payoff(long id, String email, String name, long cash, List<Line> lines) {

    /**
     * One line of a participant's result card.
     *
     * @param label what it is, in the participant's terms: "Cash at close",
     *              "STK · 4 units × 3.00"
     * @param cash  its amount in cents, or null for a line that is only said
     */
    public record Line(String label, Long cash) {
        /** A line says something. */
        public Line {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("a result line needs a label");
            }
        }
    }

    /** No lines is an empty list, never null: a run stored before 0.6 reads back as one with none. */
    public Payoff {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * A payoff with nothing to say about how it was reached: contract 0.5's.
     *
     * @param id    the person's id on the platform
     * @param email their email
     * @param name  the allotment name to carry into the settlement session
     * @param cash  the payoff, in cents; may be negative
     */
    public Payoff(long id, String email, String name, long cash) {
        this(id, email, name, cash, List.of());
    }
}
