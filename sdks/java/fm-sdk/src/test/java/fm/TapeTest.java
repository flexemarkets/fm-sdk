package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.OrderType;
import fm.model.Trade;

/**
 * A tape's own bookkeeping: which market it is for, and what it keeps once
 * full. The pairing and ordering of trades are pinned by the behaviour
 * fixtures, which all three SDKs run; none of them fills a tape, so the
 * eviction of the oldest trade had never run.
 */
class TapeTest {

    private static final Market MARKET = new Market(7L, 1L, "A", "A", "A", false, 0, 10_000, 1, 1, 100, 1);

    /** Both legs of one trade: {@code restingId} rested, {@code restingId + 1} took it at {@code price}. */
    private static Order[] _trade(long restingId, long price) {
        long aggressorId = restingId + 1;
        Instant at = Instant.parse("2026-10-08T10:00:00Z").plusSeconds(restingId);
        return new Order[] {
            new Order(at, at, restingId, restingId, restingId, aggressorId, OrderType.LIMIT, OrderSide.SELL,
                      1, price, null, 900L, 1L, 1L, "A", 7L, null, null),
            new Order(at, at, aggressorId, aggressorId, aggressorId, restingId, OrderType.LIMIT, OrderSide.BUY,
                      1, price, null, 901L, 1L, 1L, "A", 7L, null, null)
        };
    }

    @Test
    void aTapeIsForTheMarketItWasMadeFor() {
        Tape tape = new Tape(MARKET);

        assertThat(tape.market()).isSameAs(MARKET);
        assertThat(tape.marketId()).isEqualTo(7L);
    }

    @Test
    void aFullTapeDropsItsOldestTradeForTheNewest() {
        Tape tape = new Tape(MARKET, 2);

        tape.update(_trade(10, 500));
        tape.update(_trade(20, 510));
        tape.update(_trade(30, 520));

        assertThat(tape.size()).isEqualTo(2);
        assertThat(tape.mostRecentPrices()).containsExactly(510L, 520L);
        assertThat(tape.last()).extracting(Trade::price).isEqualTo(520L);
    }
}
