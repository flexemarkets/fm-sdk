package fm;


/**
 * Trading in a marketplace: submitting orders and cancelling them.
 *
 * <p>Separate from {@link Management} because they are different powers held by
 * different people. A participant trades and cannot open a session; a manager
 * runs the session and need not trade in it. Collapsing the two would mean a
 * robot that only quotes had to be handed the ability to close the market it is
 * quoting into.
 *
 * <p>All abstract, and always was: an implementation that cannot submit an
 * order is not a trading client.
 */
public interface Writing {

    /**
     * Place a limit order.
     *
     * <p>The server bounds-checks price and units against the market and
     * refuses anything off its tick grid, so round both with
     * {@link Market#priceRound} and {@link Market#unitRound} first — the grid
     * is anchored at the market's minimum, not at zero.
     *
     * @param marketplaceId the marketplace to trade in
     * @param marketId      the market within it
     * @param side          which way round the order goes
     * @param units         how many units to trade
     * @param price         the limit price, in the cents the exchange counts in
     * @return the order as the server recorded it, carrying the id it assigned
     */
    Order submitLimit(long marketplaceId, long marketId, Side side, long units, long price);

    /**
     * Cancel a resting order.
     *
     * <p>The exchange expresses a cancel as an order of its own, naming the one
     * it consumes, which is why this answers an {@link Order} rather than
     * nothing.
     *
     * @param marketplaceId the marketplace the order rests in
     * @param marketId      the market within it
     * @param originalId    the id of the order to cancel
     * @return the cancel, as the server recorded it
     */
    Order submitCancel(long marketplaceId, long marketId, long originalId);

    /**
     * Cross the book: buy at the highest price this market allows, sell at the
     * lowest.
     *
     * <p>There is no market order on the server. Its type switch falls through
     * to {@code LIMIT}, so every submission is bounds-checked against the
     * market and must sit on a tick — which is why this asks the marketplace
     * for the market first, and costs a round trip that {@link #submitLimit}
     * does not.
     *
     * <p><b>Immediate or cancel.</b> Whatever does not fill at once is
     * cancelled. Without that a market order leaves a bid at the market's
     * maximum, or an offer at its minimum, resting where anyone may take it —
     * which is not what "market order" means to the person who sent it.
     *
     * <p>So this is two calls, and the second is unconditional: the exchange
     * consumes a cancel by itself when no units remain, so a complete fill
     * costs a harmless round trip rather than an inspection that would race the
     * book. If the cancel fails the order is still placed, and this throws
     * saying so — do not resubmit, or you will trade twice.
     *
     * <p>Returns the limit order as submitted. What it filled is a property of
     * the book afterwards, not of this value.
     *
     * @param marketplaceId the marketplace to trade in
     * @param marketId      the market within it
     * @param side          which way round the order goes
     * @param units         how many units to trade
     * @return the limit order as submitted, not as filled
     */
    Order submitMarket(long marketplaceId, long marketId, Side side, long units);
}
