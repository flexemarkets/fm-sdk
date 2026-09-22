package fm.expr;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@code score} panel's fields, parsed together so that {@code f.<id>}
 * references resolve.
 *
 * <p>A field may reference an earlier field of the same panel as
 * {@code f.<id>}, and only an earlier one: intermediates are shareable, the
 * graph is acyclic by construction, and no binding or scope semantics enter
 * the language. A hidden field is a named subexpression; a visible one is a
 * row. Smith 62's profit is the reason -- units traded appears three times
 * in one payoff, and repeating a subexpression is how transcription errors
 * get in.
 *
 * <pre>
 * n      hidden   abs(now.units.WDG - open.units.WDG)
 * gross  hidden   abs(now.cash - open.cash)
 * cost   hidden   sum(take(me.valuations, f.n))
 * total           if(me.side == "S", f.gross - f.cost, f.cost - f.gross)
 * </pre>
 *
 * <p>Evaluation never throws. Each field lands as a value or as an error,
 * and a field whose input failed fails with it, so the caller renders what
 * it can and marks what it cannot -- a participant never sees a blank where
 * a number should be, and never sees a number that is wrong.
 */
public final class Panel {

    /**
     * One field as declared.
     *
     * @param id         the field's name, an identifier; what {@code f.<id>} refers to
     * @param expression its expression, parsed
     * @param show       whether it renders as a row, or is only an intermediate
     */
    public record Field(String id, Expression expression, boolean show) {
        /**
         * A field as declared.
         *
         * @param id         the field's name, an identifier
         * @param expression its expression, parsed
         * @param show       whether it renders
         */
        public Field {
            if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new ExpressionException("a field id is an identifier, not '" + id + "'");
            }
        }
    }

    /**
     * What one field evaluated to: a value, or why it did not.
     *
     * @param value the value, when evaluation succeeded
     * @param error what went wrong, when it did not
     */
    public record Outcome(Optional<Value> value, Optional<String> error) {
        static Outcome of(Value value) {
            return new Outcome(Optional.of(value), Optional.empty());
        }

        static Outcome failed(String error) {
            return new Outcome(Optional.empty(), Optional.of(error));
        }

        /**
         * Whether the field has a value.
         *
         * @return true when it evaluated
         */
        public boolean ok() {
            return value.isPresent();
        }
    }

    private final List<Field> fields;
    private final Set<String> references;

    private Panel(List<Field> fields) {
        this.fields = List.copyOf(fields);
        var refs = new LinkedHashSet<String>();
        for (var field : fields) {
            for (var reference : field.expression().references()) {
                if (!reference.startsWith("f.")) {
                    refs.add(reference);
                }
            }
        }
        this.references = Collections.unmodifiableSet(refs);
    }

    /**
     * Assemble a panel from its fields, in order, checking that every
     * {@code f.<id>} names a field declared earlier.
     *
     * @param fields the fields as declared
     * @return the panel
     * @throws ExpressionException on a duplicate id, or an {@code f.} reference
     *                             to a field not declared earlier
     */
    public static Panel of(List<Field> fields) {
        if (fields.isEmpty()) {
            throw new ExpressionException("a panel needs at least one field");
        }
        var seen = new LinkedHashSet<String>();
        for (var field : fields) {
            for (var reference : field.expression().references()) {
                if (reference.startsWith("f.") && !seen.contains(reference.substring(2))) {
                    throw new ExpressionException(reference + " is not a field declared earlier in this panel");
                }
            }
            if (!seen.add(field.id())) {
                throw new ExpressionException("duplicate field id " + field.id());
            }
        }
        return new Panel(fields);
    }

    /**
     * The fields, in declaration order.
     *
     * @return the fields
     */
    public List<Field> fields() {
        return fields;
    }

    /**
     * Every reference the panel reads from outside itself -- {@code f.*}
     * excluded, since those resolve within it.
     *
     * @return the references, in order of first appearance
     */
    public Set<String> references() {
        return references;
    }

    /**
     * The namespaces the panel reads, which decide when it recomputes.
     *
     * @return {@code me}, {@code now}, and so on, in order of first appearance
     */
    public Set<String> namespaces() {
        var out = new LinkedHashSet<String>();
        for (var reference : references) {
            out.add(reference.substring(0, reference.indexOf('.')));
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Evaluate every field against a scope, in order, with {@code f.*}
     * resolving to the fields before it.
     *
     * @param scope where the panel's references resolve
     * @return one outcome per field, keyed by id, in declaration order
     */
    public Map<String, Outcome> evaluate(Scope scope) {
        var outcomes = new LinkedHashMap<String, Outcome>();
        var earlier = new HashMap<String, Value>();
        var layered = Scope.of(earlier).then(scope);
        for (var field : fields) {
            Outcome outcome;
            try {
                var value = field.expression().evaluate(layered);
                earlier.put("f." + field.id(), value);
                outcome = Outcome.of(value);
            } catch (ExpressionException e) {
                outcome = Outcome.failed(e.getMessage());
            }
            outcomes.put(field.id(), outcome);
        }
        return Collections.unmodifiableMap(outcomes);
    }
}
