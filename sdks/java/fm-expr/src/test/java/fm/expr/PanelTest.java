package fm.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PanelTest {

    private static Panel.Field field(String id, String expr, boolean show) {
        return new Panel.Field(id, Expression.parse(expr), show);
    }

    /** smith62.json's profit panel, verbatim. */
    private static final Panel PROFIT = Panel.of(List.of(
        field("n",     "abs(now.units.WDG - open.units.WDG)", false),
        field("gross", "abs(now.cash - open.cash)", false),
        field("cost",  "sum(take(me.valuations, f.n))", false),
        field("total", "if(me.side == \"S\", f.gross - f.cost, f.cost - f.gross)", true)));

    /** smith62.json's role badge, verbatim. */
    private static final Panel ROLE = Panel.of(List.of(
        field("role", "if(me.side == \"S\", \"You are a SELLER\", \"You are a BUYER\")", true)));

    /**
     * Smith62.Analysis.profitOf, as it stands in fm-robots: the settlement the
     * panel must agree with. Ported here rather than linked so the test does
     * not depend on the study, and so a change to either side shows up.
     */
    static long profitOf(String side, long cashInitial, long cashFinal,
                         long unitsInitial, long unitsFinal, long[] valuations) {
        long units = Math.abs(unitsFinal - unitsInitial);
        long profit = Math.abs(cashFinal - cashInitial);
        for (int i = 0; i < valuations.length && i < units; i++) {
            profit -= valuations[i];
        }
        if (side.startsWith("B")) {
            profit = -profit;
        }
        return profit;
    }

    private static Scope smith62(String side, long cashInitial, long cashFinal,
                                 long unitsInitial, long unitsFinal, long... valuations) {
        var values = new HashMap<String, Value>();
        values.put("me.side", new Value.Text(side));
        values.put("me.valuations", Value.Vector.of(valuations));
        values.put("open.cash", Value.Num.of(cashInitial));
        values.put("now.cash", Value.Num.of(cashFinal));
        values.put("open.units.WDG", Value.Num.of(unitsInitial));
        values.put("now.units.WDG", Value.Num.of(unitsFinal));
        return Scope.of(values);
    }

    @ParameterizedTest(name = "{0}: cash {1}->{2}, units {3}->{4}, schedule {5}")
    @CsvSource({
        // seller: 3 units at 5/8/10, sold all three for 30.00 total
        "S,    0, 3000, 3, 0, '500,800,1000'",
        // seller sold one
        "S,    0, 1200, 3, 2, '500,800,1000'",
        // seller sold nothing
        "S,    0,    0, 3, 3, '500,800,1000'",
        // buyer with 30.00 bought two at 15/5 schedule
        "B, 3000, 1400, 0, 2, '1500,500,300'",
        // buyer bought all three
        "B, 3000,  700, 0, 3, '1500,500,300'",
        // buyer overpaid: negative profit
        "B, 3000,  200, 0, 3, '1500,500,300'",
        // traded past the schedule: excess units contribute nothing
        "S,    0, 5000, 5, 0, '500,800,1000'",
        // buyer bought nothing
        "B, 3000, 3000, 0, 0, '1500,500,300'",
    })
    @DisplayName("the profit panel agrees with Smith62.Analysis.profitOf")
    void profitAgreesWithSettlement(String side, long cashInitial, long cashFinal,
                                    long unitsInitial, long unitsFinal, String schedule) {
        var valuations = java.util.Arrays.stream(schedule.split(",")).mapToLong(Long::parseLong).toArray();
        var expected = profitOf(side, cashInitial, cashFinal, unitsInitial, unitsFinal, valuations);

        var outcomes = PROFIT.evaluate(smith62(side, cashInitial, cashFinal, unitsInitial, unitsFinal, valuations));

        assertThat(outcomes.get("total").value()).contains(Value.Num.of(expected));
        assertThat(outcomes.keySet()).containsExactly("n", "gross", "cost", "total");
    }

    @Test
    void theRoleBadgeReadsTheSide() {
        assertThat(ROLE.evaluate(Scope.of(Map.of("me.side", new Value.Text("S")))).get("role").value())
            .contains(new Value.Text("You are a SELLER"));
        assertThat(ROLE.evaluate(Scope.of(Map.of("me.side", new Value.Text("B")))).get("role").value())
            .contains(new Value.Text("You are a BUYER"));
    }

    @Test
    @DisplayName("a participant with no state row gets an error, not a default role")
    void noStateIsAnError() {
        var outcome = ROLE.evaluate(Scope.empty()).get("role");
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).contains("me.side is not available");
    }

    @Test
    @DisplayName("a failed hidden field fails what depends on it, and nothing else")
    void failurePropagatesAlongF() {
        var scope = smith62("S", 0, 3000, 3, 0, 500, 800, 1000);
        var withoutSchedule = Scope.of(Map.of(
            "me.side", new Value.Text("S"),
            "open.cash", Value.Num.of(0), "now.cash", Value.Num.of(3000),
            "open.units.WDG", Value.Num.of(3), "now.units.WDG", Value.Num.of(0)));

        var ok = PROFIT.evaluate(scope);
        assertThat(ok.values()).allMatch(Panel.Outcome::ok);

        var outcomes = PROFIT.evaluate(withoutSchedule);
        assertThat(outcomes.get("n").ok()).isTrue();
        assertThat(outcomes.get("gross").ok()).isTrue();
        assertThat(outcomes.get("cost").error()).contains("me.valuations is not available");
        assertThat(outcomes.get("total").error()).contains("f.cost is not available");
    }

    @Test
    void referencesExcludeTheFieldsAndNamespacesSayWhenToRecompute() {
        assertThat(PROFIT.references()).containsExactly(
            "now.units.WDG", "open.units.WDG", "now.cash", "open.cash", "me.valuations", "me.side");
        assertThat(PROFIT.namespaces()).containsExactly("now", "open", "me");
        assertThat(ROLE.namespaces()).containsExactly("me");
    }

    @Test
    void aFieldMayOnlyReferenceEarlierFields() {
        assertThatThrownBy(() -> Panel.of(List.of(
                field("total", "f.n * 2", true),
                field("n", "1", false))))
            .isInstanceOf(ExpressionException.class)
            .hasMessage("f.n is not a field declared earlier in this panel");
        assertThatThrownBy(() -> Panel.of(List.of(field("n", "f.n + 1", true))))
            .hasMessage("f.n is not a field declared earlier in this panel");
    }

    @Test
    void fieldIdsAreUniqueIdentifiers() {
        assertThatThrownBy(() -> Panel.of(List.of(field("n", "1", true), field("n", "2", true))))
            .hasMessage("duplicate field id n");
        assertThatThrownBy(() -> field("total profit", "1", true))
            .hasMessageContaining("a field id is an identifier");
        assertThatThrownBy(() -> Panel.of(List.of())).hasMessage("a panel needs at least one field");
    }

    @Test
    @DisplayName("a field's own f.* does not leak into the caller's scope")
    void fDoesNotReachOutside() {
        var scope = Scope.of(Map.of("f.n", Value.Num.of(99)));
        var panel = Panel.of(List.of(field("n", "1", false), field("total", "f.n", true)));
        assertThat(panel.evaluate(scope).get("total").value()).contains(Value.Num.of(1));
    }
}
