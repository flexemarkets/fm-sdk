package fm.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * How long what a limit order does not trade at once may rest (fm-server 4.30,
 * fm-server#1035).
 *
 * <p>A marketplace takes only the values its manager lists; without a list,
 * {@link #GTC} alone.
 */
public enum TimeInForce {

    /** Good till cancelled: the rest rests, as every order did before. */
    GTC,

    /** Immediate or cancel: whatever crosses trades, and the exchange cancels the rest. */
    IOC,

    /** Fill or kill: every unit trades at once, or the order is refused and nothing is written. */
    FOK;

    /**
     * The time-in-force a response names, or null for none -- good till
     * cancelled -- or one this version does not know: an unknown value should
     * cost the caller one field, not the whole response.
     *
     * @param value the time-in-force as the server spelled it, in any case
     * @return the matching value, or null for null and for anything unrecognised
     */
    @JsonCreator
    public static TimeInForce of(String value) {
        if (null == value) {
            return null;
        }
        for (var timeInForce : values()) {
            if (timeInForce.name().equalsIgnoreCase(value.trim())) {
                return timeInForce;
            }
        }
        return null;
    }
}
