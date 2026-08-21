package fm;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Holding(
    long marketplaceId,
    long sessionId,
    long allocationId,
    long ownerId,
    String name,
    long cash,
    long availableCash,
    @JsonAlias("assets") List<Security> securities) {

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
     * The position in one market, if the holder has one.
     *
     * <p>An {@link Optional} rather than a throw: a participant with no
     * position in a market is ordinary, not exceptional -- it is what every
     * holding looks like before the first allocation -- and
     * {@code IllegalArgumentException} said the caller had made a mistake
     * when they had only asked a question.
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
