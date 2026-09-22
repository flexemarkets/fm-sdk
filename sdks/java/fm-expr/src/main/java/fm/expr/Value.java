package fm.expr;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an expression evaluates to, and what a {@link Scope} hands back for a
 * reference.
 *
 * <p>Four kinds, and nothing converts between them: a number is never read as
 * text, a text never as a number, a vector never as its first element. A
 * manager transcribing a payoff formula gets a type error at configuration
 * time rather than a plausible wrong number at settlement.
 *
 * <p>Numbers are decimal throughout -- {@link BigDecimal}, never a
 * {@code double} -- so the live panel and the settlement it must agree with
 * cannot round differently. Money is whole cents and stays whole under
 * addition, subtraction and multiplication; division is the one operation
 * that can leave a fraction, and it is carried at scale six until the caller
 * formats it.
 */
public sealed interface Value {

    /**
     * A decimal number.
     *
     * @param value the number
     */
    record Num(BigDecimal value) implements Value {
        /**
         * A number from a {@code long}, which is what cents and units are.
         *
         * @param value the number
         * @return it, as a value
         */
        public static Num of(long value) {
            return new Num(BigDecimal.valueOf(value));
        }

        /**
         * A number from a string in decimal notation.
         *
         * @param value the number as written
         * @return it, as a value
         */
        public static Num of(String value) {
            return new Num(new BigDecimal(value));
        }

        /**
         * Numbers compare by value, not by scale: {@code 1} and {@code 1.0}
         * are the same number to an expression, which
         * {@link BigDecimal#equals} disagrees with.
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Num n && value.compareTo(n.value) == 0;
        }

        @Override
        public int hashCode() {
            return value.stripTrailingZeros().hashCode();
        }

        @Override
        public String toString() {
            return value.stripTrailingZeros().toPlainString();
        }
    }

    /**
     * A piece of text -- a side, a label, a group name.
     *
     * @param value the text
     */
    record Text(String value) implements Value {
        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * True or false, from a comparison.
     *
     * @param value which
     */
    record Bool(boolean value) implements Value {
        /** The two values, shared. */
        public static final Bool TRUE = new Bool(true);
        /** The two values, shared. */
        public static final Bool FALSE = new Bool(false);

        /**
         * The shared instance for a {@code boolean}.
         *
         * @param value which
         * @return {@link #TRUE} or {@link #FALSE}
         */
        public static Bool of(boolean value) {
            return value ? TRUE : FALSE;
        }

        @Override
        public String toString() {
            return Boolean.toString(value);
        }
    }

    /**
     * A vector of numbers: holdings in market order, or a declared schedule.
     *
     * @param values the entries, in order
     */
    record Vector(List<BigDecimal> values) implements Value {
        /**
         * A vector holding a defensive copy of the list.
         *
         * @param values the entries, in order
         */
        public Vector {
            values = List.copyOf(values);
        }

        /**
         * A vector from {@code long}s, which is what units and cents are.
         *
         * @param values the entries, in order
         * @return the vector
         */
        public static Vector of(long... values) {
            return new Vector(java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Vector v) || v.values.size() != values.size()) {
                return false;
            }
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).compareTo(v.values.get(i)) != 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return values.stream().map(BigDecimal::stripTrailingZeros).toList().hashCode();
        }

        @Override
        public String toString() {
            return values.stream().map(v -> v.stripTrailingZeros().toPlainString())
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
    }

    /**
     * The kind, for error messages.
     *
     * @return {@code number}, {@code text}, {@code boolean} or {@code vector}
     */
    default String kind() {
        return switch (this) {
            case Num n -> "number";
            case Text t -> "text";
            case Bool b -> "boolean";
            case Vector v -> "vector";
        };
    }
}
