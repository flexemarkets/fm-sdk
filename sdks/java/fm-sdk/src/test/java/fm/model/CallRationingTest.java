package fm.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Markets and calls built by the constructors kept for code older than
 * rationing (fm-server#1073) ration by time, as every call did then. The wire
 * fixtures cover the JSON path; these are the paths no payload reaches.
 */
class CallRationingTest {

    @Test
    void aMarketBuiltWithoutARuleRationsByTime() {
        var plain = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1);
        var indicative = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1, Market.MECHANISM_CALL, 5, true,
                2, 0, Market.CALL_BOOK_INDICATIVE);

        assertThat(plain.callRationing()).isEqualTo(Market.RATIONING_TIME);
        assertThat(indicative.callRationing()).isEqualTo(Market.RATIONING_TIME);
    }

    @Test
    void aMarketKeepsTheRuleItWasGiven() {
        var random = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1, Market.MECHANISM_CALL, 5, true,
                2, 0, Market.CALL_BOOK_SEALED, Market.RATIONING_RANDOM);

        assertThat(random.callRationing()).isEqualTo(Market.RATIONING_RANDOM);
    }

    @Test
    void aCallBuiltWithoutARuleWasByTimeAndHasNoSeed() {
        var call = new MarketCall(1, 2, 3, 4, Instant.EPOCH, MarketCall.TRIGGER_MANUAL, 550L, 4, 7, 4, 3, 2, true);

        assertThat(call.rationing()).isEqualTo(Market.RATIONING_TIME);
        assertThat(call.seed()).isNull();
    }
}
