package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** A plan is checked where it is made, so a host never stages half of one. */
class StudyPlanTest {
    private static final MarketplaceSpec PRIVATE = new MarketplaceSpec("private", "S62 private", "", List.of(MarketSpec.of("WDG", "Widget").asPrivate()), Map.of());
    private static final MarketplaceSpec PUBLIC = new MarketplaceSpec("public", "S62 public", "", List.of(MarketSpec.of("WDG", "Widget")), Map.of());
    private static final String HOLDINGS = "# holdings -- begin\nemail,name\n# holdings -- end\n";

    @Test
    void rotationsRunInOrderOnMarketplacesThePlanHas() {
        var plan = new StudyPlan(List.of(PRIVATE, PUBLIC), List.of(
            new Rotation(1, "BHSHBLSL", "private", HOLDINGS, null),
            new Rotation(2, "BLSLBHSH", "public", HOLDINGS, null),
            new Rotation(3, "SHBHSLBL", "public", HOLDINGS, null),
            new Rotation(4, "SLBLSHBH", "private", HOLDINGS, null)), null);

        assertThat(plan.rotationsIn("private")).extracting(Rotation::index).containsExactly(1, 4);
        assertThat(plan.rotationsIn("public")).extracting(Rotation::index).containsExactly(2, 3);
    }

    @Test
    void aRotationOnAMarketplaceThePlanLacksIsRefused() {
        assertThatThrownBy(() -> new StudyPlan(List.of(PRIVATE), List.of(new Rotation(1, "x", "public", HOLDINGS, null)), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("public");
    }

    @Test
    void rotationsAreNumberedFromOneInOrder() {
        assertThatThrownBy(() -> new StudyPlan(List.of(PRIVATE), List.of(new Rotation(2, "x", "private", HOLDINGS, null)), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numbered");
    }

    @Test
    void marketplaceKeysAreDistinct() {
        assertThatThrownBy(() -> new StudyPlan(List.of(PRIVATE, PRIVATE), List.of(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("share the key");
    }

    @Test
    void aMarketIsPublicByDefaultAndPrivateWhenAsked() {
        assertThat(MarketSpec.of("WDG", "Widget").privateMarket()).isFalse();
        assertThat(MarketSpec.of("WDG", "Widget").asPrivate().privateMarket()).isTrue();
        assertThat(MarketSpec.of("WDG", "Widget").priceMaximum()).isEqualTo(10_000);
    }

    @Test
    void aParticipantNeedsAnEmail() {
        assertThatThrownBy(() -> new Participant(1, " ", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Participant(1, "a@b", null, null).firstName()).isEmpty();
    }
}
