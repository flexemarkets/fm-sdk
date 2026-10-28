package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import fm.error.InvalidArgumentException;

/**
 * {@link Page#of} cuts a page as fm-server's {@code Paging.page} does, since it
 * is what every paged read does on an implementation that reads only whole
 * collections. The HTTP client's pages are the route fixtures' to check.
 */
class PageTest {

    /** A collection answered newest first, so its order is not its id order. */
    private static final List<Long> NEWEST_FIRST = List.of(50L, 40L, 30L, 20L, 10L);

    @Test
    void aPageIsTheNewestByIdInTheCollectionsOwnOrder() {
        var page = Page.of(NEWEST_FIRST, id -> id, 2, null);

        assertThat(page.rows()).containsExactly(50L, 40L);
        assertThat(page.next()).isEqualTo(40L);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void beforeReadsTheOlderRows() {
        var page = Page.of(NEWEST_FIRST, id -> id, 2, 40L);

        assertThat(page.rows()).containsExactly(30L, 20L);
        assertThat(page.next()).isEqualTo(20L);
    }

    @Test
    void aFullLastPageHasNoNext() {
        var page = Page.of(NEWEST_FIRST, id -> id, 2, 30L);

        assertThat(page.rows()).containsExactly(20L, 10L);
        assertThat(page.next()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void aPageWithNothingOlderIsEmptyAndLast() {
        var page = Page.of(NEWEST_FIRST, id -> id, 2, 10L);

        assertThat(page.rows()).isEmpty();
        assertThat(page.next()).isNull();
    }

    @Test
    void aLimitBelowOneIsRefusedAsTheServerRefusesIt() {
        assertThatThrownBy(() -> Page.of(NEWEST_FIRST, id -> id, 0, null))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void aPagesRowsCannotBeChanged() {
        var rows = new java.util.ArrayList<>(NEWEST_FIRST);
        var page = new Page<>(rows, null);
        rows.clear();

        assertThat(page.rows()).hasSize(5);
        assertThatThrownBy(() -> page.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
