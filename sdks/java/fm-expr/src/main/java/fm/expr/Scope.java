package fm.expr;

import java.util.Map;
import java.util.Optional;

/**
 * Where an expression's references resolve: {@code me.side},
 * {@code now.units.WDG}, {@code open.cash}.
 *
 * <p>The evaluator knows nothing about holdings, state rows or sessions. The
 * caller builds a scope from whatever it holds -- fm-server from a holding and
 * a state row, a study from its own files -- and the same expression means the
 * same thing in both, which is the point of there being one evaluator.
 *
 * <p>A reference the scope cannot resolve is an evaluation error, never a
 * default. A participant with no state row does not have a side of zero; they
 * have no side, and the panel says so.
 */
@FunctionalInterface
public interface Scope {

    /**
     * The value of one dotted reference, or empty if this scope has none.
     *
     * @param reference the reference as written, {@code me.side}
     * @return its value, or empty
     */
    Optional<Value> lookup(String reference);

    /**
     * A scope over a map of references to values.
     *
     * @param values the values by reference, {@code me.side} and so on
     * @return the scope; it reads the map live, not a copy
     */
    static Scope of(Map<String, Value> values) {
        return reference -> Optional.ofNullable(values.get(reference));
    }

    /**
     * An empty scope, for expressions with no references.
     *
     * @return a scope that resolves nothing
     */
    static Scope empty() {
        return reference -> Optional.empty();
    }

    /**
     * This scope, then {@code fallback} for what it lacks. A panel's fields
     * resolve {@code f.*} first and everything else through the caller's.
     *
     * @param fallback consulted when this scope has no value
     * @return the layered scope
     */
    default Scope then(Scope fallback) {
        return reference -> {
            var here = lookup(reference);
            return here.isPresent() ? here : fallback.lookup(reference);
        };
    }
}
