package fm.manifest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The type a parameter's value must satisfy.
 *
 * <p>Declared so a manager's form can be generated and their input checked
 * before it reaches a command line. A penalty typed as {@code FLOAT} is
 * rejected at the point it is entered rather than becoming a robot that fails
 * to start much later, with the reason a stack trace in a container log.
 */
public enum ValueType {
    /** Any text. Accepts whatever it is given, including the empty string. */
    STRING("string"),

    /** A whole number, wide enough to be a {@code long}. */
    INTEGER("integer"),

    /** A decimal number, e.g. a penalty or a spread. */
    FLOAT("float"),

    /** {@code true} or {@code false}, in any case. */
    BOOLEAN("boolean");

    private final String json;

    ValueType(String json) {
        this.json = json;
    }

    /**
     * How this type is spelt in a manifest.
     *
     * @return the JSON spelling, e.g. {@code integer}.
     */
    @JsonValue
    public String json() {
        return json;
    }

    /**
     * The type a manifest's spelling names.
     *
     * @param value the JSON spelling.
     * @return the matching type.
     * @throws IllegalArgumentException if no type is spelt that way. Refused
     *         rather than defaulted to {@link #STRING}, which would accept
     *         every value and check nothing.
     */
    @JsonCreator
    public static ValueType of(String value) {
        for (ValueType type : values()) {
            if (type.json.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown value type: " + value);
    }

    /**
     * True if {@code raw} is a value of this type.
     *
     * @param raw the value as it was supplied, before trimming.
     * @return whether it satisfies this type. Null never does; {@link #STRING}
     *         accepts everything else.
     */
    public boolean accepts(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            switch (this) {
                case INTEGER -> Long.parseLong(raw.trim());
                case FLOAT -> Double.parseDouble(raw.trim());
                case BOOLEAN -> {
                    String t = raw.trim();
                    if (!"true".equalsIgnoreCase(t) && !"false".equalsIgnoreCase(t)) {
                        return false;
                    }
                }
                case STRING -> {
                    return true;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
