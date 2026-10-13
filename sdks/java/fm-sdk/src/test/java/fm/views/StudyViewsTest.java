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
     * fm-ui#721: each participant is shown their own induced values -- a
     * buyer's values, a seller's costs -- read from their private state, so a
     * Smith62 class needs no panel set up by hand to tell people what a unit
     * is worth to them. Added after Profit: placements are addressed by
     * slot and index (fm-server's PanelEvaluator, fm-robots'
     * ViewConfigurationTest), so an existing panel's index must not move.
     */
    @Test
    void smith62ShowsEachParticipantTheirOwnValues_withoutMovingProfit() {
        var bottom = JsonMapper.builder().build().readTree(StudyViews.of("smith62").orElseThrow().render("WDG"))
                .path("slots").path("sidebar-bottom");
        assertThat(bottom.get(0).path("opts").path("label").asString()).isEqualTo("Profit");
        assertThat(bottom.valueStream().map(p -> p.path("opts").path("expr").asString()))
                .contains("me.valuations");
    }
}
