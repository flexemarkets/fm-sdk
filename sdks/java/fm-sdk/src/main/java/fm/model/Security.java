package fm.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

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
 *
 * @param marketId       the market this position is in
 * @param units          settled units held
 * @param availableUnits units free to sell, which excludes any already
 *                       committed to a resting order
 * @param shortUnits     the absolute short cap: the position may not fall
 *                       below {@code -shortUnits}
 * @param canBuy         whether the holder may buy in this market
 * @param canSell        whether the holder may sell in this market
 * @param initialUnits   the units the session opened with, or null when the
 *                       response did not say
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Security(
    Long marketId,
    Long units,
    Long availableUnits,
    @JsonAlias("initialShortUnits") Long shortUnits,
    Boolean canBuy,
    Boolean canSell,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long initialUnits) {

    /**
     * Substitutes zero for every absent number and false for every absent
     * flag, so a caller never has to unbox a null. A holding the server sent
     * with no short allowance reads as one permitted to short nothing, which
     * is what it means.
     */
    public Security {
        marketId       = Objects.requireNonNullElse(marketId, 0L);
        units          = Objects.requireNonNullElse(units, 0L);
        availableUnits = Objects.requireNonNullElse(availableUnits, 0L);
        shortUnits     = Objects.requireNonNullElse(shortUnits, 0L);
        canBuy         = Objects.requireNonNullElse(canBuy, Boolean.FALSE);
        canSell        = Objects.requireNonNullElse(canSell, Boolean.FALSE);
    }

    /**
     * A position whose starting units are not known.
     *
     * <p>Unlike the other numbers, {@code initialUnits} is not defaulted to
     * zero: "opened with none" and "nobody said" are different answers, and a
     * holdings export has to tell them apart (fm-robots#472). It is also left
     * out of the JSON when null, because this record is what
     * {@link Assets} posts to {@code /allocations} as {@code grants}, and a
     * request has no business carrying it.
     *
     * @param marketId       the market this position is in
     * @param units          settled units held
     * @param availableUnits units free to sell
     * @param shortUnits     the absolute short cap
     * @param canBuy         whether the holder may buy in this market
     * @param canSell        whether the holder may sell in this market
     */
    public Security(Long marketId, Long units, Long availableUnits, Long shortUnits,
                    Boolean canBuy, Boolean canSell) {
        this(marketId, units, availableUnits, shortUnits, canBuy, canSell, null);
    }
}
