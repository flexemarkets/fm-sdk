package fm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Exact conversion between the integers the exchange counts in and the
 * decimal text a person reads and types.
 *
 * <p>Every price, unit and cash amount is a {@code long} counting the smallest
 * step of its scale: with {@code decimals} of 2, {@code 5000} is
 * {@code "50.00"}. This is the one place the SDK multiplies or divides by a
 * power of ten, and it does so with {@link BigDecimal}, never {@code double}:
 * a {@code double} turns 0.29 at two decimals into 28.
 *
 * <p>{@link #parse} refuses rather than rounds. Text with a non-zero digit
 * beyond {@code decimals} is not a value the exchange can hold, and quietly
 * moving it to a neighbour is how two clients come to disagree about a price.
 * Trailing zeros are not refused: {@code "1.50"} at one decimal is exactly
 * {@code 15}.
 *
 * <p>The grammar is the same in all three SDKs: an optional {@code -}, one or
 * more digits, and optionally a {@code .} followed by one or more digits. No
 * {@code +}, no exponent, no grouping, no surrounding space.
 */
public final class Decimals {

    /** Decimals of a price when the market does not say: cents. */
    public static final int DEFAULT_PRICE_DECIMALS = 2;

    /** Decimals of a unit count when the market does not say: whole units. */
    public static final int DEFAULT_UNIT_DECIMALS = 0;

    /** Decimals of cash when the marketplace does not say: cents. */
    public static final int DEFAULT_CASH_DECIMALS = 2;

    private static final Pattern GRAMMAR = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private Decimals() {
    }

    /**
     * {@code value} as decimal text with exactly {@code decimals} digits after
     * the point, and no point when {@code decimals} is zero.
     *
     * @param value    the integer the exchange counts in
     * @param decimals the number of decimal places that integer carries
     * @return the value as text: {@code format(5000, 2)} is {@code "50.00"},
     *         {@code format(-5, 2)} is {@code "-0.05"}
     * @throws IllegalArgumentException if {@code decimals} is negative
     */
    public static String format(long value, int decimals) {
        requireDecimals(decimals);
        return BigDecimal.valueOf(value, decimals).toPlainString();
    }

    /**
     * Decimal text as the integer the exchange counts in, exactly.
     *
     * @param text     the text to read, such as {@code "50.00"}
     * @param decimals the number of decimal places the result carries
     * @return {@code text} times ten to the {@code decimals}:
     *         {@code parse("50", 2)} is {@code 5000}
     * @throws IllegalArgumentException if {@code text} is not a decimal number,
     *         has a non-zero digit beyond {@code decimals}, or does not fit a
     *         {@code long}; or if {@code decimals} is negative
     */
    public static long parse(String text, int decimals) {
        requireDecimals(decimals);
        if (null == text || !GRAMMAR.matcher(text).matches()) {
            throw new IllegalArgumentException("not a decimal number: " + quote(text));
        }
        BigDecimal scaled = new BigDecimal(text).movePointRight(decimals);
        BigInteger exact;
        try {
            exact = scaled.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    quote(text) + " has more than " + decimals + " decimal places");
        }
        if (exact.compareTo(LONG_MIN) < 0 || exact.compareTo(LONG_MAX) > 0) {
            throw new IllegalArgumentException(quote(text) + " is out of range");
        }
        return exact.longValue();
    }

    private static void requireDecimals(int decimals) {
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must not be negative: " + decimals);
        }
    }

    private static String quote(String text) {
        return null == text ? "null" : "\"" + text + "\"";
    }
}
