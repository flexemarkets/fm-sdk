package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * What a study hands the platform is checked when it is made, not when the
 * platform trips over it halfway through a class: a rotation without its
 * marketplace or holdings, or a marketplace without a market, is refused
 * at construction, and what was handed in cannot change underneath.
 */
class StudyRecordsTest {

    @Test
    void aRotationNeedsAPositionAMarketplaceAndHoldings() {
        assertThat(new Rotation(1, "practice", "mp", "holdings.csv", null).index()).isEqualTo(1);
        assertThatThrownBy(() -> new Rotation(0, "x", "mp", "holdings.csv", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("starts at 1");
        assertThatThrownBy(() -> new Rotation(2, "x", " ", "holdings.csv", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("marketplace key");
        assertThatThrownBy(() -> new Rotation(2, "x", "mp", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holdings");
    }

    @Test
    void aMarketplaceSpecNeedsAKeyAndAMarket_andKeepsItsOwnCopies() {
        List<MarketSpec> markets = new ArrayList<>(List.of(MarketSpec.of("STK", "Stock")));
        Map<String, Object> configuration = new HashMap<>(Map.of("fm.marketplace.timer", "on"));
        MarketplaceSpec spec = new MarketplaceSpec("mp", "Market", null, markets, configuration);
        markets.clear();
        configuration.clear();

        assertThat(spec.markets()).hasSize(1);
        assertThat(spec.configuration()).containsEntry("fm.marketplace.timer", "on");
        assertThat(new MarketplaceSpec("mp", "Market", null, List.of(markets(1)), null).configuration()).isEmpty();
        assertThatThrownBy(() -> new MarketplaceSpec(null, "Market", null, List.of(markets(1)), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key");
        assertThatThrownBy(() -> new MarketplaceSpec("mp", "Market", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one market");
    }

    @Test
    void aMarketIsContinuousUnlessItIsMadeACallAuction() {
        MarketSpec continuous = MarketSpec.of("STK", "Stock");
        assertThat(continuous.mechanism()).isEqualTo(MarketSpec.CONTINUOUS);
        assertThat(continuous.mechanism()).isNotEqualTo(MarketSpec.CALL);
        assertThat(new MarketSpec("A", "A", false, 0, 100, 1, 1, 10, 1, null, null, true).mechanism())
                .as("a market that does not say how it trades").isEqualTo(MarketSpec.CONTINUOUS);

        MarketSpec batch = continuous.asCall(3, false);
        assertThat(batch.mechanism()).isEqualTo(MarketSpec.CALL);
        assertThat(batch.callIntervalSeconds()).isEqualTo(3);
        assertThat(batch.callCarry()).isFalse();
        assertThat(batch.symbol()).isEqualTo("STK");
        assertThat(batch.priceMaximum()).isEqualTo(continuous.priceMaximum());

        assertThat(batch.asPrivate().mechanism()).as("a private market is continuous; a call market cannot be private")
                .isEqualTo(MarketSpec.CONTINUOUS);
        assertThat(continuous.asCall(null, true).callIntervalSeconds()).as("calls only when a manager clears").isNull();
    }

    @Test
    void aHoldingKeepsItsOwnSecurities() {
        List<ParticipantHolding.Security> securities = new ArrayList<>(List.of(new ParticipantHolding.Security("STK", 10, 8)));
        ParticipantHolding holding = new ParticipantHolding(1, "a@uni.edu", "A", 1000, 900, securities);
        securities.clear();

        assertThat(holding.securities()).extracting(ParticipantHolding.Security::units).containsExactly(8L);
        assertThat(new ParticipantHolding(2, "b@uni.edu", "B", 0, 0, null).securities()).isEmpty();
    }

    private static MarketSpec markets(int n) {
        return MarketSpec.of("S" + n, "Security " + n);
    }
}
