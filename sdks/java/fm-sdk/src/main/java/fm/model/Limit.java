package fm.model;

/**
 * A limit order to place: {@link fm.role.Writing#submitLimit(long, long, OrderSide, long, long, String, Long, String)
 * submitLimit}'s arguments, as one value, for {@link fm.role.Writing#submitLimits} to take several of. The
 * marketplace is the call's, and the submission id is made when it is sent.
 *
 * @param marketId          the market within the marketplace
 * @param side              which way round the order goes
 * @param units             how many units to trade
 * @param price             the limit price, an integer at the market's {@link Market#priceDecimals}
 * @param ownerTarget       the counterparty this order is for, by allotment name; null when it names none
 *                          or names it by id
 * @param ownerTargetId     the counterparty this order is for, by owner id; null when it names none or
 *                          names it by name
 * @param clientDescription this order's reference; null for the client's own
 */
public record Limit(long marketId, OrderSide side, long units, long price,
                    String ownerTarget, Long ownerTargetId, String clientDescription) {

    /**
     * An ordinary order: no counterparty, and the client's own reference.
     *
     * @param marketId the market within the marketplace
     * @param side     which way round the order goes
     * @param units    how many units to trade
     * @param price    the limit price, an integer at the market's {@link Market#priceDecimals}
     */
    public Limit(long marketId, OrderSide side, long units, long price) {
        this(marketId, side, units, price, null, null, null);
    }
}
