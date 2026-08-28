package fm.manifest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a parameter appears on the command line. */
public enum ParameterType {
    /** A bare argument in a fixed position, e.g. {@code PENALTY}. */
    POSITIONAL("positional", 1),

    /** A named flag with a value, e.g. {@code --payoffs "[2 0 1; ...]"}. */
    OPTION("option", 1),

    /**
     * A repeating pair of bare arguments, e.g. {@code (SYMBOL SPREAD)...}.
     * The MVO robots take one pair per market, so the count is not known until
     * the marketplace is.
     */
    POSITIONAL_PAIRS("positional-pairs", 2),

    /**
     * A repeating triple, e.g. {@code (SIDE SYMBOL PRICE)...}.
     *
     * <p>PLATFORM-ROBOTS.md §4.2 declared only pairs, because it was written from
     * the MVO robots. fm-maker and fm-taker take triples, so the schema was
     * short of the fleet it describes.
     */
    POSITIONAL_TRIPLES("positional-triples", 3);

    private final String _json;
    private final int _groupSize;

    ParameterType(String json, int groupSize) {
        this._json = json;
        this._groupSize = groupSize;
    }

    /**
     * How many bare arguments make one logical entry. Used to check a supplied
     * value is whole: three symbols and two prices is a typo, not a spread
     * list, and it should be caught before it becomes a robot that will not
     * start.
     *
     * @return the number of bare arguments per entry; 1 for the types that do
     *         not repeat.
     */
    public int groupSize() {
        return _groupSize;
    }

    /**
     * True if this type repeats, so its tokens must be emitted last.
     *
     * @return whether one declaration can contribute many arguments.
     */
    public boolean repeating() {
        return _groupSize > 1;
    }

    /**
     * How this type is spelt in a manifest.
     *
     * @return the JSON spelling, e.g. {@code positional-pairs}.
     */
    @JsonValue
    public String json() {
        return _json;
    }

    /**
     * The type a manifest's spelling names.
     *
     * @param value the JSON spelling.
     * @return the matching type.
     * @throws IllegalArgumentException if no type is spelt that way. Refused
     *         rather than defaulted: a misspelt type would otherwise become an
     *         option, and its value would reach the robot in the wrong place.
     */
    @JsonCreator
    public static ParameterType of(String value) {
        for (ParameterType type : values()) {
            if (type._json.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown parameter type: " + value);
    }
}
