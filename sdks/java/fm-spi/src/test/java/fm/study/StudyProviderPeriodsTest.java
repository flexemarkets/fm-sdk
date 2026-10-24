package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fm.manifest.ParameterSpec;

/**
 * Contract 0.1's per-period step is optional: a study that plans every
 * rotation at setup -- Smith 62 -- neither carries nor reads what was
 * carried, and settles exactly as it did under 0.0.
 */
class StudyProviderPeriodsTest {

    private static final Rotation ROTATION = new Rotation(1, "Period 1", "mp", "holdings.csv", null);
    private static final Settlement PAID = new Settlement(List.of(), List.of("Name"), List.of());

    /** A 0.0 study: one that overrides only the three-argument settle. */
    private static final class Planned implements StudyProvider {
        @Override public String id() { return "planned"; }
        @Override public String name() { return "Planned"; }
        @Override public String description() { return ""; }
        @Override public List<ParameterSpec> parameters() { return List.of(); }
        @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public Settlement settle(Map<String, String> parameters, Rotation rotation,
                List<ParticipantHolding> holdings) {
            return PAID;
        }
    }

    @Test
    void aStudyThatPlansEveryPeriodCarriesNothing() {
        assertThatThrownBy(() -> new Planned().carry(Map.of(), ROTATION, List.of(), null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("planned");
    }

    /** The host calls the four-argument settle; a 0.0 study's own settle still answers it. */
    @Test
    void whatWasCarriedReachesAStudyThatIgnoresItAsItsOwnSettle() {
        assertThat(new Planned().settle(Map.of(), ROTATION, List.of(), "draws=3,1")).isSameAs(PAID);
    }

    @Test
    void aCarryNeedsTheNextPeriodsHoldings() {
        assertThat(new Carry("holdings.csv", null, null).carried()).isNull();
        assertThatThrownBy(() -> new Carry(" ", "state.csv", "draws=3"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holdings");
    }

    @Test
    void theContractIsZeroPointTwo() {
        assertThat(new Planned().studySpiVersion()).isEqualTo("0.2");
    }
}
