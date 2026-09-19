package fm.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExpressionTest {

    private static Value eval(String source) {
        return Expression.parse(source).evaluate(Scope.empty());
    }

    private static Value eval(String source, Map<String, Value> values) {
        return Expression.parse(source).evaluate(Scope.of(values));
    }

    private static Value.Num num(String value) {
        return Value.Num.of(value);
    }

    @Nested
    class Arithmetic {

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
            "1 + 2,           3",
            "1 + 2 * 3,       7",
            "(1 + 2) * 3,     9",
            "10 - 4 - 3,      3",
            "2 * 3 * 4,       24",
            "-3 + 5,          2",
            "-(3 + 5),        -8",
            "--3,             3",
            "7 / 2,           3.5",
            "1 / 3,           0.333333",
            "2 / 3,           0.666667",
            "0.1 + 0.2,       0.3",
            "1500 * 0.05,     75",
        })
        void precedenceAndValue(String source, String expected) {
            assertThat(eval(source)).isEqualTo(num(expected));
        }

        @Test
        @DisplayName("addition of cents is exact, not binary floating point")
        void decimalNotDouble() {
            // 0.1 + 0.2 == 0.3 is the whole reason for BigDecimal.
            assertThat(eval("0.1 + 0.2 == 0.3")).isEqualTo(Value.Bool.TRUE);
        }

        @Test
        void divisionByZeroIsAnEvaluationError() {
            assertThatThrownBy(() -> eval("1 / 0"))
                .isInstanceOf(ExpressionException.class)
                .hasMessage("division by zero");
        }

        @Test
        void textDoesNotAdd() {
            assertThatThrownBy(() -> eval("\"Period \" + 3"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("+ needs a number, not a text");
        }
    }

    @Nested
    class Comparison {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1 == 1", "1 == 1.0", "1 != 2", "1 < 2", "2 <= 2", "3 > 2", "2 >= 2",
                                "\"S\" == \"S\"", "\"S\" != \"B\""})
        void trueCases(String source) {
            assertThat(eval(source)).isEqualTo(Value.Bool.TRUE);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1 == 2", "1 != 1", "2 < 1", "\"S\" == \"B\""})
        void falseCases(String source) {
            assertThat(eval(source)).isEqualTo(Value.Bool.FALSE);
        }

        @Test
        void kindsDoNotCompare() {
            assertThatThrownBy(() -> eval("\"1\" == 1"))
                .isInstanceOf(ExpressionException.class)
                .hasMessage("cannot compare text with number (==)");
        }

        @Test
        void textDoesNotOrder() {
            assertThatThrownBy(() -> eval("\"B\" < \"S\""))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("only numbers order");
        }

        @Test
        void comparisonsDoNotChain() {
            assertThatThrownBy(() -> Expression.parse("1 < 2 < 3"))
                .isInstanceOf(ExpressionException.class)
                .hasMessage("comparisons do not chain at 6");
        }
    }

    @Nested
    class Functions {

        @Test
        void ifTakesTheArmItsConditionPicks() {
            assertThat(eval("if(1 == 1, \"yes\", \"no\")")).isEqualTo(new Value.Text("yes"));
            assertThat(eval("if(1 == 2, \"yes\", \"no\")")).isEqualTo(new Value.Text("no"));
        }

        @Test
        @DisplayName("if evaluates only the arm it takes")
        void ifIsLazy() {
            // The other arm divides by zero and references nothing available.
            assertThat(eval("if(1 == 1, 5, 1 / 0)")).isEqualTo(num("5"));
            assertThat(eval("if(1 == 2, me.absent, 5)")).isEqualTo(num("5"));
        }

        @Test
        void ifNeedsAComparison() {
            assertThatThrownBy(() -> eval("if(1, 2, 3)"))
                .isInstanceOf(ExpressionException.class)
                .hasMessage("if needs a comparison first, not a number");
        }

        @Test
        void abs() {
            assertThat(eval("abs(-7)")).isEqualTo(num("7"));
            assertThat(eval("abs(7)")).isEqualTo(num("7"));
        }

        @Test
        void sum() {
            assertThat(eval("sum(me.v)", Map.of("me.v", Value.Vector.of(15, 5, 3)))).isEqualTo(num("23"));
            assertThat(eval("sum(me.v)", Map.of("me.v", Value.Vector.of()))).isEqualTo(num("0"));
        }

        @Test
        void takeIsAPrefix() {
            var scope = Map.<String, Value>of("me.v", Value.Vector.of(15, 5, 3));
            assertThat(eval("take(me.v, 2)", scope)).isEqualTo(Value.Vector.of(15, 5));
            assertThat(eval("take(me.v, 0)", scope)).isEqualTo(Value.Vector.of());
        }

        @Test
        @DisplayName("take past the end is the whole vector -- a schedule consumed past its end contributes nothing")
        void takePastTheEnd() {
            assertThat(eval("take(me.v, 9)", Map.of("me.v", Value.Vector.of(15, 5, 3))))
                .isEqualTo(Value.Vector.of(15, 5, 3));
        }

        @Test
        void takeNeedsAWholeNonNegativeCount() {
            var scope = Map.<String, Value>of("me.v", Value.Vector.of(15, 5, 3));
            assertThatThrownBy(() -> eval("take(me.v, -1)", scope)).hasMessageContaining("whole number");
            assertThatThrownBy(() -> eval("take(me.v, 1.5)", scope)).hasMessageContaining("whole number");
        }

        @Test
        void wrongKindIsNamed() {
            assertThatThrownBy(() -> eval("sum(3)")).hasMessage("sum needs a vector, not a number");
            assertThatThrownBy(() -> eval("abs(\"x\")")).hasMessage("abs needs a number, not a text");
        }

        @Test
        void unknownFunctionAndArity() {
            assertThatThrownBy(() -> Expression.parse("pow(2, 3)")).hasMessage("unknown function pow at 0");
            assertThatThrownBy(() -> Expression.parse("abs(1, 2)")).hasMessage("abs takes 1 argument, not 2 at 0");
            assertThatThrownBy(() -> Expression.parse("if(1 == 1, 2)")).hasMessage("if takes 3 arguments, not 2 at 0");
        }
    }

    @Nested
    class References {

        @Test
        void resolveThroughTheScope() {
            assertThat(eval("me.side", Map.of("me.side", new Value.Text("S")))).isEqualTo(new Value.Text("S"));
            assertThat(eval("now.units.WDG * 2", Map.of("now.units.WDG", num("3")))).isEqualTo(num("6"));
        }

        @Test
        void aMissingReferenceIsAnErrorNotADefault() {
            assertThatThrownBy(() -> eval("me.side", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessage("me.side is not available");
        }

        @Test
        void bareNamesAreRefused() {
            assertThatThrownBy(() -> Expression.parse("cash + 1"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("references are namespaced");
        }

        @Test
        void areCollectedInOrder() {
            var e = Expression.parse("if(me.side == \"S\", now.cash - open.cash, me.side)");
            assertThat(e.references()).containsExactly("me.side", "now.cash", "open.cash");
            assertThat(e.namespaces()).containsExactly("me", "now", "open");
        }

        @Test
        void quotedTextIsNotAReference() {
            var e = Expression.parse("\"me.side\"");
            assertThat(e.references()).isEmpty();
            assertThat(eval("\"me.side\"")).isEqualTo(new Value.Text("me.side"));
        }
    }

    @Nested
    class Parsing {

        @ParameterizedTest(name = "<{0}>")
        @ValueSource(strings = {
            "",
            "   ",
            "1 +",
            "(1 + 2",
            "1 + 2)",
            "\"unterminated",
            "1 = 1",
            "1.2.3",
            "me.",
            "me..side",
            "1 2",
            "a; b",
            "me.side.constructor()",
            "System.exit(0)",
            "new Function(\"x\")",
            "{}",
            "[1, 2]",
            "me.side ? 1 : 2",
            "me.side && 1",
        })
        void malformedInputFailsToParse(String source) {
            assertThatThrownBy(() -> Expression.parse(source)).isInstanceOf(ExpressionException.class);
        }

        @Test
        void aParseErrorCarriesItsPosition() {
            var e = org.assertj.core.api.Assertions.catchThrowableOfType(
                    ExpressionException.class, () -> Expression.parse("1 + (2 * 3"));
            assertThat(e.position()).isEqualTo(10);
            assertThat(e).hasMessage("expected ')' but found end of expression at 10");
        }

        @Test
        void stringsEscape() {
            assertThat(eval("\"say \\\"hi\\\"\"")).isEqualTo(new Value.Text("say \"hi\""));
        }

        @Test
        void sourceRoundTrips() {
            assertThat(Expression.parse(" 1 + 2 ").source()).isEqualTo(" 1 + 2 ");
        }
    }
}
