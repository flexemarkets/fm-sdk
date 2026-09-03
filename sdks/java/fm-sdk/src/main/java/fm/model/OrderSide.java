package fm.model;

import fm.Orders;
import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Which way an order goes.
 *
 * <p>Was a {@code String} with {@code Order.SIDE_BUY} and {@code SIDE_SELL}
 * beside it as constants, which is a convention rather than a rule: nothing
 * stopped a caller passing {@code "buy"}, {@code "Buy"} or {@code "BYU"}, and
 * the first two worked because every comparison in the SDK was
 * {@code equalsIgnoreCase} while the third reached the server and came back a
 * 400. As an enum the invalid case cannot be written.
 *
 * <p>Null is a real value on the wire and is not represented here: a cancel
 * carries no side, so {@code Order.side()} is null for one. That is the
 * server's shape, not something an {@code UNSPECIFIED} constant would improve.
 */
public enum OrderSide {

    /** Buying: the bid side of the book. */
    BUY,

    /** Selling: the offer side of the book. */
    SELL;

    /**
     * The other side. What a maker quotes against, and what a taker lifts.
     *
     * <p>Replaces {@code Orders.contra(String)}, which had to spell the
     * pairing out and could be handed something that was neither.
     *
     * @return SELL for BUY, and BUY for SELL
     */
    public OrderSide contra() {
        return this == BUY ? SELL : BUY;
    }

    /**
     * The side a response names, or null if it names none or one this version
     * does not know.
     *
     * <p>Deliberately lenient. Jackson's default is to throw on an unknown enum
     * value, which would fail an entire response — an order list, a holdings
     * snapshot — over one field the caller may not even read. A null side is
     * recoverable and localised; a refused response is neither.
     *
     * @param value the side as the server spelled it, in any case
     * @return the matching side, or null for null and for anything
     *         unrecognised
     */
    @JsonCreator
    public static OrderSide of(String value) {
        if (null == value) {
            return null;
        }
        for (var side : values()) {
            if (side.name().equalsIgnoreCase(value.trim())) {
                return side;
            }
        }
        return null;
    }
}
