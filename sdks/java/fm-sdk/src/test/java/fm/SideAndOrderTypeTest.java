package fm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Side and OrderType, before anything uses them.
 *
 * <p>Both were Strings with constants beside them, which is a convention rather
 * than a rule: nothing stopped a caller writing "buy", "Buy" or "BYU". The
 * first two worked because every comparison in the SDK was equalsIgnoreCase;
 * the third reached the server and came back a 400.
 *
 * <p>The lenience on the way in is the part worth pinning. Jackson's default is
 * to throw on an unknown enum value, which would fail an entire response -- an
 * order list, a holdings snapshot -- over one field the caller may not read.
 * The server has emitted "MARKET" on at least one path, so this is not
 * hypothetical.
 */
class SideAndOrderTypeTest {

    @Test
    void aSideIsReadWhateverItsCasing() {
        assertThat(Side.of("BUY")).isEqualTo(Side.BUY);
        assertThat(Side.of("buy")).isEqualTo(Side.BUY);
        assertThat(Side.of(" Sell ")).isEqualTo(Side.SELL);
    }

    @Test
    void anUnknownSideIsNullRatherThanAThrow() {
        assertThat(Side.of("BYU")).isNull();
        assertThat(Side.of(null)).isNull();
        assertThat(Side.of("")).isNull();
    }

    @Test
    void contraPairsTheTwoSides() {
        assertThat(Side.BUY.contra()).isEqualTo(Side.SELL);
        assertThat(Side.SELL.contra()).isEqualTo(Side.BUY);
    }

    @Test
    void anOrderTypeIsReadWhateverItsCasing() {
        assertThat(OrderType.of("LIMIT")).isEqualTo(OrderType.LIMIT);
        assertThat(OrderType.of("cancel")).isEqualTo(OrderType.CANCEL);
    }

    /** The server has sent this; a strict enum would refuse the whole response. */
    @Test
    void anUnknownOrderTypeIsNullRatherThanAThrow() {
        assertThat(OrderType.of("MARKET")).isNull();
        assertThat(OrderType.of(null)).isNull();
    }
}
