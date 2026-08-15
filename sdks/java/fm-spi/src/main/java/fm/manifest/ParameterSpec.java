package fm.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One declared parameter of a robot: what it is called, how it reaches the
 * command line, and what a valid value looks like.
 *
 * <p>Unknown properties are ignored so that a manifest written against a newer
 * schema still loads on an older server. The alternative — refusing to start a
 * robot because its manifest mentions a field this build has not heard of — is
 * a worse failure than skipping the field.
 *
 * @param name       stable identifier, used as the key in stored configuration
 *                   and in launch requests. Never shown to a user.
 * @param cli        how it appears on the command line: {@code --payoffs} for an
 *                   option, {@code PENALTY} for a positional, {@code SYMBOL SPREAD}
 *                   for a pair.
 * @param type       positional, option, or repeating pair.
 * @param valueType  the type a value must satisfy. Null for
 *                   {@link ParameterType#POSITIONAL_PAIRS}, which uses
 *                   {@code keyValueType} instead, and for platform parameters,
 *                   whose values the platform supplies.
 * @param label      human-readable name for the manager's form.
 * @param description help text for the same form.
 * @param required   whether a value must be present before a launch is allowed.
 * @param defaultValue value used when none is supplied. Bound from the JSON key
 *                   {@code default}, which is a Java keyword.
 * @param unit       display-only suffix, e.g. {@code ms}.
 * @param source     for {@link Ownership#PLATFORM} parameters only: where the
 *                   server gets the value, e.g. {@code session-token}.
 * @param minPairs   for pairs only: the fewest acceptable.
 * @param keyValueType for pairs only: the types of each half.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParameterSpec(
        String name,
        String cli,
        ParameterType type,
        ValueType valueType,
        String label,
        String description,
        boolean required,
        @JsonProperty("default") String defaultValue,
        String unit,
        String source,
        Integer minPairs,
        KeyValueType keyValueType) {

    /**
     * The types of each half of a {@link ParameterType#POSITIONAL_PAIRS} entry.
     *
     * @param key   the type of the first token, e.g. a symbol.
     * @param value the type of the second, e.g. a spread.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyValueType(ValueType key, ValueType value) {}

    /**
     * True if this parameter can be left unset at launch time.
     *
     * @return true unless it is required and has no default to fall back to.
     */
    public boolean optional() {
        return !required || defaultValue != null;
    }

    /**
     * The value to use when the manager or participant supplied none, or null
     * if there is nothing to fall back to.
     *
     * @param supplied what was supplied, possibly null or blank.
     * @return {@code supplied} if it has content, otherwise the declared
     *         default, otherwise null.
     */
    public String effective(String supplied) {
        return supplied != null && !supplied.isBlank() ? supplied : defaultValue;
    }
}
