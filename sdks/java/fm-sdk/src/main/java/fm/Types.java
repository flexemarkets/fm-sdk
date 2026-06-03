package fm;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public class Types {
    private Types() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiRoot(Map<String, LinkObject> _links) {
        public record LinkObject(String href) {}

        public Optional<String> getLink(String name) {
            if (_links == null) return Optional.empty();
            var link = _links.get(name);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Security(
        Long marketId,
        Long units,
        Long availableUnits,
        Boolean canBuy,
        Boolean canSell) {

        public Security {
            marketId       = Objects.requireNonNullElse(marketId, 0L);
            units          = Objects.requireNonNullElse(units, 0L);
            availableUnits = Objects.requireNonNullElse(availableUnits, 0L);
            canBuy         = Objects.requireNonNullElse(canBuy, Boolean.FALSE);
            canSell        = Objects.requireNonNullElse(canSell, Boolean.FALSE);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Allotment(
        Long id,
        Long allocationId,
        Long marketplaceId,
        Long ownerId,
        String name,
        Assets assets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assets(
        Long id,
        String name,
        long cash,
        @JsonAlias("grants") List<Security> securities) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientConnection(
        long marketplaceId,
        @JsonAlias("id") long connectionId,
        long ownerId,
        String established,
        String terminated,
        String description) {
    }

    public record Version(int version) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConflictFailure(
        String status,
        String error,
        String message,
        String path,
        String suggestedName) {
    }
}
