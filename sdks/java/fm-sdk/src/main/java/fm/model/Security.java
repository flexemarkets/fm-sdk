package fm.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
 * <p>A live session's floor is the grant's allowance plus what a manager's
 * RIGHTS adjustments have moved it by, so {@code shortUnits} read from an
 * Asset is {@code initialShortUnits + adjustedShortUnits}
 * ({@link #fromWire}). Reading the grant's alone left a robot on the old
 * floor after a manager raised or lowered it.
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
 * @param adjustedUnits  units a manager's adjustments gave (+) or took (-),
 *                       net, since the open: in {@code units}, but not put
 *                       there by trading, so the units trading made are
 *                       {@code units - initialUnits - adjustedUnits}. Null
 *                       when the response did not say (an allotment, or a
 *                       server before 4.30, which had no adjustments)
 * @param adjustedShortUnits the change adjustments made to the short
 *                       allowance, already in {@code shortUnits}; null as
 *                       {@code adjustedUnits}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Security(
    Long marketId,
    Long units,
    Long availableUnits,
    Long shortUnits,
    Boolean canBuy,
    Boolean canSell,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long initialUnits,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long adjustedUnits,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long adjustedShortUnits) {

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

    /**
     * A position with its starting units, and nothing said about adjustments.
     *
     * <p>The canonical constructor until 0.22, kept so callers that name
     * {@code initialUnits} still compile.
     *
     * @param marketId       the market this position is in
     * @param units          settled units held
     * @param availableUnits units free to sell
     * @param shortUnits     the absolute short cap
     * @param canBuy         whether the holder may buy in this market
     * @param canSell        whether the holder may sell in this market
     * @param initialUnits   the units the session opened with, or null
     */
    public Security(Long marketId, Long units, Long availableUnits, Long shortUnits,
                    Boolean canBuy, Boolean canSell, Long initialUnits) {
        this(marketId, units, availableUnits, shortUnits, canBuy, canSell, initialUnits, null, null);
    }

    /**
     * Built from the wire, which names the floor one of two ways.
     *
     * <p>{@code shortUnits} -- the allotments path, or a position written
     * back -- is the floor as it stands. fm-server's Asset sends the grant's
     * allowance ({@code initialShortUnits}) and what adjustments moved it by
     * ({@code adjustedShortUnits}) apart, and the floor is their sum.
     */
    @JsonCreator
    static Security fromWire(
            @JsonProperty("marketId") Long marketId,
            @JsonProperty("units") Long units,
            @JsonProperty("availableUnits") Long availableUnits,
            @JsonProperty("shortUnits") Long shortUnits,
            @JsonProperty("initialShortUnits") Long initialShortUnits,
            @JsonProperty("canBuy") Boolean canBuy,
            @JsonProperty("canSell") Boolean canSell,
            @JsonProperty("initialUnits") Long initialUnits,
            @JsonProperty("adjustedUnits") Long adjustedUnits,
            @JsonProperty("adjustedShortUnits") Long adjustedShortUnits) {
        Long floor = null != shortUnits
                ? shortUnits
                : Long.valueOf(Objects.requireNonNullElse(initialShortUnits, 0L)
                        + Objects.requireNonNullElse(adjustedShortUnits, 0L));
        return new Security(marketId, units, availableUnits, floor, canBuy, canSell,
                initialUnits, adjustedUnits, adjustedShortUnits);
    }
}
