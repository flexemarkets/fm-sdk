package fm.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * What an order is: a bid or offer, or the withdrawal of one.
 *
 * <p>Was a {@code String} with {@code Order.TYPE_LIMIT} and {@code TYPE_CANCEL}
 * beside it. There is no {@code MARKET}: the server's type switch shares its
 * default with {@code LIMIT}, so a market order is a limit order at the extreme
 * legal price and {@code Writing.submitMarket} sends {@code LIMIT} — naming a
 * constant the exchange does not have would suggest otherwise.
 */
public enum OrderType {

    /** A priced order, which rests on the book until filled or cancelled. */
    LIMIT,

    /** An order that consumes a resting one, removing it from the book. */
    CANCEL;

    /**
     * The type a response names, or null if it names none or one this version
     * does not know.
     *
     * <p>Lenient for the reason {@link OrderSide#of} gives: an unknown value should
     * cost the caller one field, not the whole response. It matters more here —
     * the server has emitted {@code "MARKET"} on at least one path, which a
     * strict enum would have turned into a parse failure for every order in the
     * list.
     *
     * @param value the type as the server spelled it, in any case
     * @return the matching type, or null for null and for anything
     *         unrecognised, including {@code "MARKET"}
     */
    @JsonCreator
    public static OrderType of(String value) {
        if (null == value) {
            return null;
        }
        for (var type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
