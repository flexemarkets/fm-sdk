package fm;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A position in one market, and how far short the holder may go.
 *
 * <p>{@code shortUnits} is the absolute floor: the position may not fall
 * below {@code -shortUnits}, so
 * {@code availableUnits == units + shortUnits}.
 *
 * <p>It reaches the client under two names. A live session's holdings
 * arrive through fm-server's {@code Asset}, which emits
 * {@code initialShortUnits} -- the grant's immutable starting allowance --
 * and no {@code shortUnits}; the allotments/Grant path emits
 * {@code shortUnits} directly. Both are accepted, so a caller reads the
 * same number regardless of which response produced the holding. Requests
 * carry {@code shortUnits}, which is what {@code /allocations} reads.
 *
 * <p>The field was absent until 0.0.10, and its absence was silent: the
 * record ignores unknown properties, so every holding the SDK parsed came
 * back with no short allowance rather than with an error. A participant
 * permitted to short 50 read as one permitted to short nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Security(
    Long marketId,
    Long units,
    Long availableUnits,
    @JsonAlias("initialShortUnits") Long shortUnits,
    Boolean canBuy,
    Boolean canSell) {

    public Security {
        marketId       = Objects.requireNonNullElse(marketId, 0L);
        units          = Objects.requireNonNullElse(units, 0L);
        availableUnits = Objects.requireNonNullElse(availableUnits, 0L);
        shortUnits     = Objects.requireNonNullElse(shortUnits, 0L);
        canBuy         = Objects.requireNonNullElse(canBuy, Boolean.FALSE);
        canSell        = Objects.requireNonNullElse(canSell, Boolean.FALSE);
    }
}
