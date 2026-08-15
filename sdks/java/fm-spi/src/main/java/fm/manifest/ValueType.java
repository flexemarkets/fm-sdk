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
    STRING("string"),
    INTEGER("integer"),
    FLOAT("float"),
    BOOLEAN("boolean");

    private final String json;

    ValueType(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ValueType of(String value) {
        for (ValueType type : values()) {
            if (type.json.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown value type: " + value);
    }

    /** True if {@code raw} is a value of this type. */
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
