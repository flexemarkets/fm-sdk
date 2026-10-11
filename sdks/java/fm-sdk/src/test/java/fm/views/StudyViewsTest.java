package fm.views;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class StudyViewsTest {
    @Test
    void shipsSmith62() {
        assertThat(StudyViews.all()).extracting(StudyView::id).contains("smith62");
        assertThat(StudyViews.of("smith62")).isPresent();
        assertThat(StudyViews.of("nope")).isEmpty();
    }

    @Test
    void everyViewIsADocumentOnceItsSymbolIsFilledIn() {
        for (StudyView view : StudyViews.all()) {
            var rendered = view.render("WDG");
            assertThat(rendered).doesNotContain(StudyView.SYMBOL);
            var root = JsonMapper.builder().build().readTree(rendered);
            assertThat(root.path("v").asInt()).as(view.id()).isEqualTo(1);
            assertThat(root.path("slots").isObject()).as(view.id()).isTrue();
            assertThat(view.label()).isNotBlank();
            assertThat(view.description()).isNotBlank();
        }
    }

    @Test
    void smith62ReadsTheMarketItIsGiven() {
        var view = StudyViews.of("smith62").orElseThrow();
        assertThat(view.render("ABC")).contains("now.units.ABC - open.units.ABC").doesNotContain("WDG");
        var root = JsonMapper.builder().build().readTree(view.render("WDG"));
        assertThat(root.path("slots").path("header").size()).isEqualTo(2);
        assertThat(root.path("state").path("private").path("valuations").path("type").asString()).isEqualTo("vector");
    }

    /**
     * SSW's sequestered dividends: what a participant has earned, shown under
     * the trading panels, read from the private state fm-ssw uploads. It lived
     * only in fm-robots' study, so the server could not offer it.
     */
    @Test
    void sswShowsTheDividendsEarnedFromPrivateState() {
        var view = StudyViews.of("ssw").orElseThrow();
        var root = JsonMapper.builder().build().readTree(view.render("WDG"));
        var score = root.path("slots").path("sidebar-bottom").get(0);
        assertThat(score.path("panel").asString()).isEqualTo("score");
        assertThat(score.path("opts").path("expr").asString()).isEqualTo("me.dividends");
        assertThat(score.path("opts").path("format").asString()).isEqualTo("cents");
        assertThat(root.path("state").path("private").path("dividends").path("unit").asString()).isEqualTo("cents");
    }
}
