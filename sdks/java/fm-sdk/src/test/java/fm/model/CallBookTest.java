package fm.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A market built by the constructors kept for code older than the
 * indicative price is sealed, and only a call market can show one. The wire
 * fixtures cover the JSON path; these are the paths no payload reaches.
 */
class CallBookTest {

    @Test
    void aMarketBuiltWithoutABookIsSealed() {
        var plain = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1);
        var scaled = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1, Market.MECHANISM_CALL, 5, true, 2, 0);

        assertThat(plain.callBook()).isEqualTo(Market.CALL_BOOK_SEALED);
        assertThat(scaled.callBook()).isEqualTo(Market.CALL_BOOK_SEALED);
        assertThat(scaled.showsIndicative()).isFalse();
    }

    @Test
    void onlyAnIndicativeCallMarketShowsItsIndicativePrice() {
        var call = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1, Market.MECHANISM_CALL, 5, true, 2, 0,
                Market.CALL_BOOK_INDICATIVE);
        var continuous = new Market(1, 2, "A", "", "A", false, 0, 100, 1, 1, 100, 1, Market.MECHANISM_CONTINUOUS,
                null, true, 2, 0, Market.CALL_BOOK_INDICATIVE);

        assertThat(call.showsIndicative()).isTrue();
        assertThat(continuous.showsIndicative()).as("the book setting means nothing without calls").isFalse();
    }
}
