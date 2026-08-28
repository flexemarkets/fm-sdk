package fm;

import java.time.Instant;


/**
 * One trade: the resting order, the order that crossed it, and the numbers
 * each side contributes.
 *
 * <p>A trade is not a distinct thing on the wire. The exchange expresses one as
 * a pair of orders referring to each other, so every number a caller wants has
 * to be read off one side or the other -- and <em>which</em> side is the part
 * that is easy to get wrong. Both sides are kept here, and the derived
 * components record the choice rather than leaving each caller to make it
 * again:
 *
 * <ul>
 *   <li>{@link #price} and {@link #units} come from {@link #resting}, which
 *       carries the terms the trade happened on.</li>
 *   <li>{@link #at} comes from {@link #aggressor}, because the trade happened
 *       when the incoming order arrived, not when the quote it took was
 *       posted.</li>
 * </ul>
 *
 * <p>The pairing rule is the one {@code TradesSummary} in fm-manager has always
 * used: an order that is a consumed limit whose consumer is also a limit is one
 * side of a match, and {@link OrderUtils#isResting} says which side. Before
 * this type existed the tape kept only the resting order, so "who took this
 * trade" answered with the maker -- a real participant, at a real price, in a
 * complete-looking line that named the wrong person.
 *
 * @param resting   the order that was already on the book and got taken
 * @param aggressor the incoming order that crossed it
 * @param price     the price the trade happened at, from {@code resting}
 * @param units     the size, from {@code resting}
 * @param at        when the aggressor arrived, or null when the server did not
 *                  stamp it
 */
public record Trade(Order resting, Order aggressor, long price, long units, Instant at) {

    /**
     * A trade from its two sides, taking each derived component off the side
     * that carries it.
     *
     * @param resting   the order that was already on the book
     * @param aggressor the incoming order that crossed it
     * @return the pair, with price and units from {@code resting} and the time
     *         from {@code aggressor}
     * @throws NullPointerException if either side is null
     */
    public static Trade of(Order resting, Order aggressor) {
        if (resting == null) throw new NullPointerException("Resting order is required.");
        if (aggressor == null) throw new NullPointerException("Aggressor order is required.");
        return new Trade(resting, aggressor,
            resting.price(), resting.units(), aggressor.lastModifiedDate());
    }
}
