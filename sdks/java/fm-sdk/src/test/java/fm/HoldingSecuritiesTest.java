package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;


import org.junit.jupiter.api.Test;

/**
 * One way to read a holding's positions, not two that disagree.
 *
 * <p>The record accessor {@code securities()} gave whatever the wire sent --
 * possibly null, in arrival order -- while {@code getSecurities()} sorted and
 * substituted an empty list. Which one a caller reached for decided whether
 * their code could NPE and whether two holdings compared equal, and nothing in
 * either name said so.
 */
class HoldingSecuritiesTest {

    private static Security security(long marketId, long units) {
        return new Security(marketId, units, units, 0L, true, true);
    }

    private static Holding holding(List<Security> securities) {
        return new Holding(1L, 300L, 42L, 8L, "alice", 10_000L, 10_000L, securities);
    }

    @Test
    void positionsComeBackInMarketOrderWhateverOrderTheyArrivedIn() {
        var out = holding(List.of(security(30, 3), security(10, 1), security(20, 2)));

        assertThat(out.securities()).extracting(Security::marketId)
                .containsExactly(10L, 20L, 30L);
    }

    /** The old accessor returned the wire's null straight through. */
    @Test
    void aHoldingWithNoPositionsReadsAsEmptyRatherThanNull() {
        assertThat(holding(null).securities()).isEmpty();
    }

    /**
     * Worth having in its own right: equality used to depend on the order the
     * server happened to list positions in, so two holdings of the same thing
     * could compare unequal.
     */
    @Test
    void twoHoldingsOfTheSamePositionsAreEqual() {
        assertThat(holding(List.of(security(10, 1), security(20, 2))))
                .isEqualTo(holding(List.of(security(20, 2), security(10, 1))));
    }

    /**
     * Having no position in a market is ordinary -- it is what every holding
     * looks like before the first allocation -- so asking is a question, not a
     * mistake. It used to throw IllegalArgumentException.
     */
    @Test
    void askingForAPositionTheHolderDoesNotHaveIsEmptyNotAnError() {
        var out = holding(List.of(security(10, 1)));

        assertThat(out.security(10)).contains(security(10, 1));
        assertThat(out.security(99)).isEmpty();
    }
}
