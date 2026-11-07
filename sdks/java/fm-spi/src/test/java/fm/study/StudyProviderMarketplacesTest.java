package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fm.manifest.ParameterSpec;
import fm.study.RobotSpec.Mode;

/**
 * Contract 0.5 lets a host create a run's marketplaces before anyone takes
 * part, and plan its people later. A study that says nothing gets the
 * marketplaces its own plan makes for stand-ins.
 */
class StudyProviderMarketplacesTest {

    private static final String HOLDINGS = "# holdings -- begin\nemail,name\n# holdings -- end\n";
    private static final MarketplaceSpec MARKET = new MarketplaceSpec("main", "Main", "", List.of(MarketSpec.of("WDG", "Widget")), Map.of());

    /** A 0.4 study: one plan for whoever takes part, and robots of its own. */
    private static class Planned implements StudyProvider {
        List<Participant> participants;
        Participant manager;
        Map<String, Participant> robots;

        @Override public String id() { return "planned"; }
        @Override public String name() { return "Planned"; }
        @Override public String description() { return ""; }
        @Override public List<ParameterSpec> parameters() { return List.of(); }

        @Override public List<RobotSpec> robots(Map<String, String> parameters) {
            return List.of(
                new RobotSpec("good", "fm-venture-entrepreneur", "main", Mode.OWN, Map.of(), null),
                new RobotSpec("mvo", "fm-taker-mvo", "main", Mode.OFFERED, null, null));
        }

        @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants) {
            throw new UnsupportedOperationException("a host calls the four-argument setup");
        }

        @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants,
                Participant manager, Map<String, Participant> robots) {
            if (participants.isEmpty()) {
                throw new IllegalArgumentException("Planned needs at least one participant");
            }
            this.participants = participants;
            this.manager = manager;
            this.robots = robots;
            return new StudyPlan(List.of(MARKET), List.of(new Rotation(1, "one", "main", HOLDINGS, null)), null);
        }
    }

    @Test
    void aStudyThatSaysNothingGetsTheMarketplacesItsPlanMakes() {
        assertThat(new Planned().marketplaces(Map.of())).containsExactly(MARKET);
    }

    /**
     * Every study refuses to plan for nobody, so the default plans for
     * stand-ins: one participant, a manager, and a person for each robot of
     * its own -- the people a plan is handed -- none of them real.
     */
    @Test
    void theDefaultPlansForStandIns_noneOfThemReal() {
        Planned study = new Planned();

        study.marketplaces(Map.of());

        assertThat(study.participants).hasSize(1);
        assertThat(study.participants.get(0).id()).isNegative();
        assertThat(study.manager.id()).isNegative();
        assertThat(study.robots).containsOnlyKeys("good");
        assertThat(study.robots.get("good").id()).isNegative();
        assertThat(study.participants.get(0).email()).endsWith(".invalid");
        assertThat(study.robots.get("good").email()).endsWith(".invalid");
    }

    @Test
    void aStudyRefusesAParameterHereAsItWouldInItsPlan() {
        StudyProvider refusing = new Planned() {
            @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants,
                    Participant manager, Map<String, Participant> robots) {
                throw new IllegalArgumentException("cash must be a whole number");
            }
        };

        assertThatThrownBy(() -> refusing.marketplaces(Map.of("cash", "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("cash must be a whole number");
    }

    @Test
    void aStudyMayAnswerFromItsParametersAlone() {
        StudyProvider direct = new Planned() {
            @Override public List<MarketplaceSpec> marketplaces(Map<String, String> parameters) {
                return List.of(MARKET);
            }

            @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants,
                    Participant manager, Map<String, Participant> robots) {
                throw new AssertionError("not planned to answer what it needs");
            }
        };

        assertThat(direct.marketplaces(Map.of())).containsExactly(MARKET);
    }

    @Test
    void theContractIsFive() {
        assertThat(new Planned().studySpiVersion()).isEqualTo("0.5");
    }
}
