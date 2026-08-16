package fm;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public class Types {
    private Types() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiRoot(@JsonProperty("_links") Map<String, LinkObject> links) {
        /**
         * HAL sends {@code templated} alongside {@code href}, and may send more.
         * Annotated like every other type here so the record survives a
         * consumer's own ObjectMapper: the SDK's mapper disables
         * FAIL_ON_UNKNOWN_PROPERTIES globally, but a published type should not
         * depend on the configuration of whoever reads it.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record LinkObject(String href) {}

        /**
         * The component is {@code links}; the wire name is {@code _links}.
         *
         * <p>The server speaks HAL, which spells the link envelope with a
         * leading underscore, and that is not a name a Java accessor should
         * carry. {@code @JsonProperty} keeps the binding exact in both
         * directions, so this reads as {@code links()} while still consuming
         * and producing {@code _links}. The Python and TypeScript SDKs already
         * expose it as {@code links}; this brings Java into line without
         * changing a byte of what crosses the network.
         */
        public Optional<String> getLink(String name) {
            if (links == null) return Optional.empty();
            var link = links.get(name);
            return link != null ? Optional.of(link.href()) : Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Person(
        String createdDate,
        String lastModifiedDate,
        Long id,
        Long accountId,
        String firstName,
        String lastName,
        String email,
        String[] roles,
        Boolean accountOwner) {

        public Person {
            id           = Objects.requireNonNullElse(id, 0L);
            accountId    = Objects.requireNonNullElse(accountId, 0L);
            accountOwner = Objects.requireNonNullElse(accountOwner, Boolean.FALSE);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(
        String createdDate,
        String lastModifiedDate,
        Long id,
        String name,
        String description,
        Person owner,
        boolean approval,
        String approvalDescription) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Token(
        String requestUrl,
        Person person,
        Account account,
        String token) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(
        long marketplaceId,
        long allocationId,
        long id,
        long original,
        String state,
        String name,
        String description,
        String openDate,
        String closeDate) {

        public static final String STATE_INIT   = "INIT";
        public static final String STATE_OPEN   = "OPEN";
        public static final String STATE_PAUSED = "PAUSED";
        public static final String STATE_CLOSED = "CLOSED";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Marketplace(
        long id,
        String name,
        String description,
        List<Market> markets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Market(
        long id,
        long marketplaceId,
        String name,
        String description,
        String symbol,
        boolean privateMarket,
        long priceMinimum,
        long priceMaximum,
        long priceTick,
        long unitMinimum,
        long unitMaximum,
        long unitTick) {

        public long priceRound(long price) {
            return Math.min(Math.max((price - price % priceTick), priceMinimum), priceMaximum);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Order(
        String createdDate,
        String lastModifiedDate,
        long id,
        long original,
        long supplier,
        Long consumer,
        String type,
        String side,
        long units,
        long price,
        @JsonIgnore
        Boolean mine,
        Long ownerId,
        long marketplaceId,
        long sessionId,
        String symbol,
        long marketId,
        String ownerTarget,
        String clientDescription) {

        public static final String TYPE_LIMIT  = "LIMIT";
        public static final String TYPE_CANCEL = "CANCEL";
        public static final String SIDE_BUY  = "BUY";
        public static final String SIDE_SELL = "SELL";
    }

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

        public Security getSecurity(long marketId) {
            for (var security : securities) {
                if (marketId == security.marketId()) {
                    return security;
                }
            }
            throw new IllegalArgumentException("Security for market ID " + marketId + " not found.");
        }

        public List<Security> getSecurities() {
            if (securities == null) {
                return Collections.emptyList();
            }
            return securities.stream()
                .sorted(Comparator.comparingLong(Security::marketId))
                .toList();
        }
    }

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

    /**
     * A participant's opening position in an allocation.
     *
     * <p>The server spells the nested capital {@code capital} on some responses
     * and {@code assets} on others, so both are accepted; requests send
     * {@code assets}, which is what {@code /allocations} reads.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Allotment(
        Long id,
        Long allocationId,
        Long marketplaceId,
        Long ownerId,
        String name,
        @JsonAlias("capital") Assets assets) {
    }

    /**
     * The capital half of an allotment: opening cash and opening positions.
     *
     * <p>The positions cross the wire as {@code grants} in BOTH directions --
     * that is the server's own {@code Assets.grants} -- while the component
     * stays {@code securities} so it reads the same as
     * {@link Holding#securities()} on this side.
     *
     * <p>Hence {@code @JsonProperty}, which binds both ways, and not
     * {@code @JsonAlias} alone, which binds only on the way in. That was the
     * original defect: nothing serialized this type, so being half-bound cost
     * nothing until {@code allocate()} posted one -- at which point the request
     * would carry "securities", the server would find no "grants", and the
     * allocation would be created with the cash and no positions. Accepted,
     * 200, and quietly wrong. The alias is kept so a response spelling it
     * "securities" still parses.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assets(
        Long id,
        String name,
        long cash,
        @JsonProperty("grants") @JsonAlias("securities") List<Security> securities) {
    }

    /**
     * A participant's attachment to a marketplace, and the session it belonged
     * to.
     *
     * <p>{@code sessionId} is nullable because a connection outlives any one
     * session: an open browser tab spans a pause and re-open. It is how a study
     * works out who was present in a particular run, so its absence -- the
     * record simply did not have the component until 0.0.11 -- meant every
     * connection read as belonging to no session at all.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientConnection(
        long marketplaceId,
        @JsonAlias("id") long connectionId,
        long ownerId,
        String established,
        String terminated,
        String description,
        Long sessionId) {
    }

    public record Version(int version) {}

    /**
     * One-time passcodes a manager mints on behalf of their users.
     *
     * <p>Credentials, and short-lived: {@code expiresAt} is when the whole
     * bundle stops working, not a per-entry deadline. Handing these out is how
     * a classroom gets its students logged in without issuing passwords, which
     * is also why nothing here should be logged.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManagerOtpBundle(String expiresAt, List<Entry> otps) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Entry(long userId, String email, String otp) {
        }
    }

    /**
     * The server's answer to an approval request: the account as it now
     * stands, plus what was asked of it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Approval(
        Account account,
        String description,
        Boolean approve) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConflictFailure(
        String status,
        String error,
        String message,
        String path,
        String suggestedName) {
    }
}
