package fm.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The constructors kept for code built before per-market decimals (fm-robots
 * builds markets and marketplaces with them) give the scales of today's
 * server, and an explicit scale, zero included, is kept as given. The wire
 * fixtures cover the JSON path; these are the paths no payload reaches.
 */
class DecimalsDefaultTest {

    @Test
    void aMarketBuiltWithoutDecimalsCountsCentsAndWholeUnits() {
        var plain = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1);
        var call = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1, Market.MECHANISM_CALL, 5, false);

        assertThat(plain.priceDecimals()).isEqualTo(2);
        assertThat(plain.unitDecimals()).isEqualTo(0);
        assertThat(call.priceDecimals()).isEqualTo(2);
        assertThat(call.unitDecimals()).isEqualTo(0);
        assertThat(call.isCall()).isTrue();
        assertThat(call.callCarry()).isFalse();
    }

    @Test
    void aMarketsOwnScaleIsKeptEvenAtZero() {
        var market = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1, null, null, null, 0, 3);

        assertThat(market.priceDecimals()).isEqualTo(0);
        assertThat(market.unitDecimals()).isEqualTo(3);
    }

    @Test
    void aMarketplaceBuiltWithoutACashScaleCountsCents() {
        assertThat(new Marketplace(1, "M", "", List.of()).cashDecimals()).isEqualTo(2);
        assertThat(new Marketplace(1, "M", "", List.of(), 0).cashDecimals()).isEqualTo(0);
    }
}
