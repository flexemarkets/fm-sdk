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
}
