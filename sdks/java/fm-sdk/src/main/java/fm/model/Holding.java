package fm.model;

import fm.role.Reading;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What one participant holds in a marketplace: cash, and a position per market.
 *
 * <p>A holding belongs to a session. {@link Reading#holdings(long)} answers for
 * the current one; a settlement report reads a finished run's through
 * {@link Reading#holdings(long, java.util.List)}.
 *
 * <p>Cash and units each come in two forms. The settled figure is what the
 * participant owns; the available figure is what they may still commit, which
 * is lower by whatever is already promised to a resting order. A robot that
 * gates on the settled figure stops trading while the server would happily
 * have accepted the order.
 *
 * <p>The starting figures, {@code initialCash} here and
 * {@link Security#initialUnits()} per market, are what the session opened
 * with. fm-server's live holdings carry both; a response that does not leaves
 * them null, and a caller should treat null as "not known", not as zero.
 *
 * @param marketplaceId the marketplace this holding is in
 * @param sessionId     the session it belongs to
 * @param allocationId  the allocation it started from
 * @param ownerId       the participant it belongs to
 * @param name          the participant's display name
 * @param cash          settled cash, in the cents the exchange counts in
 * @param availableCash cash free to spend, which excludes any committed to a
 *                      resting buy
 * @param securities    the position in each market; never null, always in
 *                      market order
 * @param initialCash   the cash the participant started the session with, or
 *                      null when the response did not say; see below
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Holding(
    long marketplaceId,
    long sessionId,
    long allocationId,
    long ownerId,
    String name,
    long cash,
    long availableCash,
    @JsonAlias("assets") List<Security> securities,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long initialCash) {

    /**
     * Never null, and always in market order.
     *
     * <p>There used to be two ways to read the positions and they disagreed.
     * The record accessor {@code securities()} gave whatever the wire sent
     * -- possibly null, in arrival order -- while {@code getSecurities()}
     * sorted and substituted an empty list. Which one a caller reached for
     * decided whether their code could NPE and whether two holdings
     * compared equal, and nothing in either name said so.
     *
     * <p>Normalising here leaves one accessor that is always safe, so the
     * question cannot be got wrong. It also makes {@code equals} mean what
     * a reader expects: two holdings with the same positions are equal
     * regardless of the order the server happened to list them in.
     */
    public Holding {
        securities = null == securities
                ? List.of()
                : securities.stream()
                    .sorted(Comparator.comparingLong(Security::marketId))
                    .toList();
    }

    /**
     * A holding whose starting cash is not known.
     *
     * <p>{@code initialCash} came late, and every caller that builds a
     * holding by hand -- tests, fixtures, fakes -- predates it. It stays null
     * rather than zero or {@code cash}: a holdings export that cannot tell the
     * starting cash should leave the column empty, not write a number that
     * looks right. fm-robots#472 is what happened when it wrote {@code cash}.
     *
     * @param marketplaceId the marketplace this holding is in
     * @param sessionId     the session it belongs to
     * @param allocationId  the allocation it started from
     * @param ownerId       the participant it belongs to
     * @param name          the participant's display name
     * @param cash          settled cash
     * @param availableCash cash free to spend
     * @param securities    the position in each market
     */
    public Holding(long marketplaceId, long sessionId, long allocationId, long ownerId,
                   String name, long cash, long availableCash, List<Security> securities) {
        this(marketplaceId, sessionId, allocationId, ownerId, name, cash, availableCash, securities, null);
    }

    /**
     * The position in one market, if the holder has one.
     *
     * <p>An {@link Optional} rather than a throw: a participant with no
     * position in a market is ordinary, not exceptional -- it is what every
     * holding looks like before the first allocation -- and
     * {@code IllegalArgumentException} said the caller had made a mistake
     * when they had only asked a question.
     *
     * @param marketId the market to look up
     * @return that market's position, or empty if the holder has none
     */
    public Optional<Security> security(long marketId) {
        for (var security : securities) {
            if (marketId == security.marketId()) {
                return Optional.of(security);
            }
        }
        return Optional.empty();
    }
}
