package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import fm.model.Market;
import fm.model.TickGrid;

/**
 * The rationing form of createCallMarket is a default, so an implementation
 * written against 0.22 -- one that has only the eight-argument form --
 * still compiles. It may make a market by time, which is what that form
 * makes; asked for any other rule it refuses, rather than make a market
 * that clears differently from the one asked for.
 */
class CreateCallMarketRationingTest {

    /** An implementation from before rationing: the eight-argument form only. */
    private static final class Before extends FakeFlexemarkets {
        int made;

        Before() {
            super(List.of(), null, null);
        }

        @Override
        public Market createCallMarket(long mp, String symbol, String name, TickGrid price, TickGrid units,
                                       Integer intervalSeconds, boolean carry, String callBook) {
            made++;
            return new Market(12, mp, name, "", symbol, false, price.minimum(), price.maximum(), price.tick(),
                    units.minimum(), units.maximum(), units.tick(), Market.MECHANISM_CALL, intervalSeconds, carry,
                    2, 0, callBook);
        }
    }

    private static final TickGrid PRICE = new TickGrid(0, 10000, 1);

    @Test
    void byTimeOrUnsaidGoesToTheOlderForm() {
        var fm = new Before();

        var byTime = fm.createCallMarket(1, "BAT", "Batch", PRICE, TickGrid.units(), 5, true, null,
                Market.RATIONING_TIME);
        var unsaid = fm.createCallMarket(1, "BAT", "Batch", PRICE, TickGrid.units(), 5, true, null, null);

        assertThat(fm.made).isEqualTo(2);
        assertThat(byTime.callRationing()).isEqualTo(Market.RATIONING_TIME);
        assertThat(unsaid.callRationing()).isEqualTo(Market.RATIONING_TIME);
    }

    @Test
    void anyOtherRuleIsRefusedAndNothingIsMade() {
        var fm = new Before();

        assertThatThrownBy(() -> fm.createCallMarket(1, "RND", "Random", PRICE, TickGrid.units(), 5, true, null,
                Market.RATIONING_RANDOM))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(Market.RATIONING_RANDOM);
        assertThat(fm.made).isZero();
    }
}
