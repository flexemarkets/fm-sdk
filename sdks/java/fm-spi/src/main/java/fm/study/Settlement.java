package fm.study;

import java.util.List;

/**
 * What {@link StudyProvider#settle} produces: a payoff per participant, the
 * report a manager reads, and what the study reveals to everyone.
 *
 * <p>{@code reveal} is what the room may now be told, shown on every result
 * card and the projector: "Outcome A happened", "STK paid 1.00 a unit", the
 * values each group was given. A study that hid nothing, or says nothing,
 * leaves it empty (contract 0.6).
 *
 * @param payoffs one per participant the rotation was for
 * @param columns the report's column headings
 * @param rows    one row per participant, in the study's own order, each
 *                as wide as {@code columns}; numbers are already text
 * @param reveal  what everyone may now be told, line by line; empty for nothing
 */
public record Settlement(List<Payoff> payoffs, List<String> columns, List<List<String>> rows, List<String> reveal) {
    /** Every row is as wide as the columns, or the report cannot be read. */
    public Settlement {
        payoffs = payoffs == null ? List.of() : List.copyOf(payoffs);
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        reveal = reveal == null ? List.of() : List.copyOf(reveal);
        for (List<String> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("a settlement row must be as wide as its columns, " + columns.size());
            }
        }
    }

    /**
     * A settlement that reveals nothing: contract 0.5's.
     *
     * @param payoffs one per participant the rotation was for
     * @param columns the report's column headings
     * @param rows    one row per participant, each as wide as {@code columns}
     */
    public Settlement(List<Payoff> payoffs, List<String> columns, List<List<String>> rows) {
        this(payoffs, columns, rows, List.of());
    }
}
