package fm.role;

import java.util.List;

import fm.PendingOrder;
import fm.model.Limit;
import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;


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
     * @param price         the limit price, an integer at the market's
     *                      {@link fm.model.Market#priceDecimals}
     * @return the order as the server recorded it, carrying the id it assigned
     */
    Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price);

    /**
     * Place a limit order naming the counterparty it is for.
     *
     * <p>What a private market requires. A private market matches a LIMIT order
     * only against one whose owner and target are its own target and owner, so
     * an order with no target never trades there — the server refuses it
     * outright rather than resting it. Without this there is no call in this
     * SDK that trades in a private market at all.
     *
     * <p>The target is an owner id, not a name. The server takes a name as a
     * fallback for clients that still send one, but says itself that it is
     * ambiguous: an allotment name is a display label, and the {@code ----}
     * sentinel is shared by every hidden participant. So the id is what this
     * asks for, and resolving a name to one is the caller's business —
     * the caller is the one holding the allotment the name came from.
     *
     * @param marketplaceId the marketplace to trade in
     * @param marketId      the market within it
     * @param side          which way round the order goes
     * @param units         how many units to trade
     * @param price         the limit price, an integer at the market's
     *                      {@link fm.model.Market#priceDecimals}
     * @param ownerTargetId the counterparty this order is for; null for an
     *                      ordinary untargeted order in a public market
     * @return the order as the server recorded it, carrying the id it assigned
     */
    Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price,
                      Long ownerTargetId);

    /**
     * Place a limit order naming its counterparty either way, with its own reference.
     *
     * <p>The id is the unambiguous target, but only a manager can learn ids: a
     * participant's {@code identifiers} are names. So a participant trading in a
     * private market has the allotment name and nothing else, and passes it as
     * {@code ownerTarget}. Name one or the other, not both.
     *
     * <p>{@code clientDescription} is this order's own reference, as the server
     * keeps it on the order; null takes the description this client connected
     * with.
     *
     * @param marketplaceId     the marketplace to trade in
     * @param marketId          the market within it
     * @param side              which way round the order goes
     * @param units             how many units to trade
     * @param price             the limit price, an integer at the market's
     *                      {@link fm.model.Market#priceDecimals}
     * @param ownerTarget       the counterparty this order is for, by allotment name;
     *                          null when it names none or names it by id
     * @param ownerTargetId     the counterparty this order is for, by owner id; null
     *                          when it names none or names it by name
     * @param clientDescription this order's reference; null for the client's own
     * @return the order as the server recorded it, carrying the id it assigned
     * @throws fm.error.InvalidArgumentException when both targets are given
     */
    Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price,
                      String ownerTarget, Long ownerTargetId, String clientDescription);

    /**
     * Place a limit order without waiting for the answer.
     *
     * <p>The order goes over the marketplace's socket and this returns a
     * {@link PendingOrder} at once: the next order can go before this one is
     * matched, and fm-server still matches them in the order sent. It completes
     * with the order, or with the refusal {@link #submitLimit} would have
     * thrown -- or {@link fm.error.OrderOutcomeUnknownException} if the socket
     * closed before the answer came. The socket opens with the first order sent
     * this way and stays open until the client is closed; at most 64 orders are
     * unanswered at once, and this waits, in turn, while they are. See
     * docs/SUBMIT-RATE.md.
     *
     * <p>On a client connected with {@link fm.OrderRoute#REST} the order goes
     * over REST instead: one {@code POST .../orders/batch} is in flight at a
     * time, and the orders sent while it is out -- at most 64 -- go together as
     * the next, in the order sent. It then completes with the order or the
     * refusal, or {@link fm.error.OrderOutcomeUnknownException} if the request
     * was sent twice without a readable answer. Needs fm-server 4.19.
     *
     * @param marketplaceId the marketplace to trade in
     * @param marketId      the market within it
     * @param side          which way round the order goes
     * @param units         how many units to trade
     * @param price         the limit price, an integer at the market's
     *                      {@link fm.model.Market#priceDecimals}
     * @return the order, sent and not yet answered
     */
    PendingOrder sendLimit(long marketplaceId, long marketId, OrderSide side, long units, long price);

    /**
     * {@link #sendLimit(long, long, OrderSide, long, long)}, naming its
     * counterparty either way and with its own reference, as
     * {@link #submitLimit(long, long, OrderSide, long, long, String, Long, String)} does.
     *
     * @param marketplaceId     the marketplace to trade in
     * @param marketId          the market within it
     * @param side              which way round the order goes
     * @param units             how many units to trade
     * @param price             the limit price, an integer at the market's
     *                          {@link fm.model.Market#priceDecimals}
     * @param ownerTarget       the counterparty this order is for, by allotment name;
     *                          null when it names none or names it by id
     * @param ownerTargetId     the counterparty this order is for, by owner id; null
     *                          when it names none or names it by name
     * @param clientDescription this order's reference; null for the client's own
     * @return the order, sent and not yet answered
     * @throws fm.error.InvalidArgumentException when both targets are given
     */
    PendingOrder sendLimit(long marketplaceId, long marketId, OrderSide side, long units, long price,
                           String ownerTarget, Long ownerTargetId, String clientDescription);

    /**
     * Place several limit orders in one request, matched in the order given.
     *
     * <p>One {@code POST .../orders/batch} of up to 64 orders; a longer list
     * goes as several, one after the other. Each order is matched before the
     * next, as if sent one at a time with {@link #submitLimit}, for one round
     * trip instead of one each. Returns one {@link PendingOrder} per order, in
     * order, already answered: it completes with the order, or with what
     * {@link #submitLimit} would have thrown for it. One refusal does not stop
     * the rest.
     *
     * <p>A request whose answer was lost is sent once more, whole: every order
     * names its submission, and fm-server answers one it already placed with
     * the order it made. A failure of the whole request -- the token, the
     * connection -- is thrown, as {@link #submitLimit} throws it. Needs
     * fm-server 4.19. See docs/SUBMIT-RATE.md, section 5.
     *
     * @param marketplaceId the marketplace to trade in
     * @param limits        the orders, in the order they are to be matched
     * @return one answered order per limit, in the same order
     * @throws fm.error.InvalidArgumentException when a limit names both targets;
     *                                           nothing is sent
     */
    List<PendingOrder> submitLimits(long marketplaceId, List<Limit> limits);

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
     * Cancel a resting order without waiting for the answer.
     *
     * <p>{@link #submitCancel} over the marketplace's socket, as
     * {@link #sendLimit(long, long, OrderSide, long, long)} is
     * {@link #submitLimit(long, long, OrderSide, long, long)}: the cancel goes on
     * the same socket as the limits sent there, through the same window, and
     * fm-server takes them in the order sent -- a cancel and the limit sent after
     * it reach the book in that order. It completes with the CANCEL order the
     * exchange recorded, or with the refusal {@link #submitCancel} would have
     * thrown -- or {@link fm.error.OrderOutcomeUnknownException} if the socket
     * closed before the answer came.
     *
     * <p>{@code originalId} is the order's {@link Order#original()}: for the
     * order a {@code sendLimit} or {@code submitLimit} answered, its
     * {@link Order#id()}. Unlike {@link #submitCancel}'s DELETE, which looks the
     * order up, the socket's CANCEL goes to the exchange as named, and the id of
     * a fragment a split left behind matches nothing -- the CANCEL is recorded,
     * consumed by itself, and nothing is cancelled. An order not yet answered has
     * no id and cannot be cancelled this way.
     *
     * @param marketplaceId the marketplace the order rests in
     * @param marketId      the market within it; on the wire, as the socket's
     *                      CANCEL names its market
     * @param originalId    the original id of the order to cancel
     * @return the cancel, sent and not yet answered
     */
    PendingOrder sendCancel(long marketplaceId, long marketId, long originalId);

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
    Order submitMarket(long marketplaceId, long marketId, OrderSide side, long units);
}
