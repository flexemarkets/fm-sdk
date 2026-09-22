package fm.study;

import java.util.List;

/**
 * What {@link StudyProvider#settle} produces: a payoff per participant, and
 * the report a manager reads.
 *
 * @param payoffs one per participant the rotation was for
 * @param columns the report's column headings
 * @param rows    one row per participant, in the study's own order, each
 *                as wide as {@code columns}; numbers are already text
 */
public record Settlement(List<Payoff> payoffs, List<String> columns, List<List<String>> rows) {
    public Settlement {
        payoffs = payoffs == null ? List.of() : List.copyOf(payoffs);
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        for (List<String> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("a settlement row must be as wide as its columns, " + columns.size());
            }
        }
    }
}
