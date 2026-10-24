package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fm.manifest.ParameterSpec;
import fm.study.RobotSpec.Mode;

/**
 * Contract 0.3 lets a study name robots: its own traders, which the host
 * gives people and launches, or robots it offers participants with fixed
 * parameters. Both are optional: a 0.2 study names none, and plans as it did.
 */
class StudyProviderRobotsTest {

    private static final Participant MANAGER = new Participant(1, "m@uni.edu", "M", "");
    private static final Participant ALICE = new Participant(2, "a@uni.edu", "A", "");
    private static final Participant GOOD = new Participant(3, "good@robots", "Good", "");

    /** A 0.2 study: no robots, and a plan that is told the manager. */
    private static class Planned implements StudyProvider {
        Participant manager;

        @Override public String id() { return "planned"; }
        @Override public String name() { return "Planned"; }
        @Override public String description() { return ""; }
        @Override public List<ParameterSpec> parameters() { return List.of(); }
        @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants,
                Participant manager) {
            this.manager = manager;
            return null;
        }
    }

    @Test
    void aStudyWithoutRobotsNamesNone_andPlansAsItDid() {
        Planned study = new Planned();

        assertThat(study.robots(Map.of())).isEmpty();
        study.setup(Map.of(), List.of(ALICE), MANAGER, Map.of());
        assertThat(study.manager).isEqualTo(MANAGER);
    }

    @Test
    void theContractIsThree() {
        assertThat(new Planned().studySpiVersion()).isEqualTo("0.3");
    }

    @Test
    void aStudyWithRobotsOfItsOwnPlansWithTheirPeople() {
        Map<String, Participant> given = new HashMap<>();
        StudyProvider venture = new Planned() {
            @Override public List<RobotSpec> robots(Map<String, String> parameters) {
                return List.of(new RobotSpec("good", "fm-venture-entrepreneur", "venture", Mode.OWN,
                        Map.of("failure-rate", "0.25"), Set.of()));
            }
            @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants,
                    Participant manager, Map<String, Participant> robots) {
                given.putAll(robots);
                return null;
            }
        };

        assertThat(venture.robots(Map.of())).extracting(RobotSpec::key).containsExactly("good");
        venture.setup(Map.of(), List.of(ALICE), MANAGER, Map.of("good", GOOD));
        assertThat(given).containsEntry("good", GOOD);
    }

    @Test
    void aRobotRunsInItsMarketplace_inTheRotationsItNames_orInAllOfThem() {
        RobotSpec everywhere = new RobotSpec("mvo", "fm-taker-mvo", "capm", Mode.OFFERED, null, null);
        RobotSpec second = new RobotSpec("good", "fm-venture-entrepreneur", "venture", Mode.OWN, Map.of(), Set.of(2));

        assertThat(everywhere.parameters()).isEmpty();
        assertThat(everywhere.runsIn(new Rotation(1, "1", "capm", "h.csv", null))).isTrue();
        assertThat(everywhere.runsIn(new Rotation(1, "1", "other", "h.csv", null))).isFalse();
        assertThat(second.runsIn(new Rotation(1, "1", "venture", "h.csv", null))).isFalse();
        assertThat(second.runsIn(new Rotation(2, "2", "venture", "h.csv", null))).isTrue();
    }

    @Test
    void aRobotNeedsItsNames_andAMode() {
        assertThatThrownBy(() -> new RobotSpec(" ", "r", "mp", Mode.OWN, null, null)).hasMessageContaining("key");
        assertThatThrownBy(() -> new RobotSpec("k", null, "mp", Mode.OWN, null, null)).hasMessageContaining("robot id");
        assertThatThrownBy(() -> new RobotSpec("k", "r", "", Mode.OWN, null, null)).hasMessageContaining("marketplace");
        assertThatThrownBy(() -> new RobotSpec("k", "r", "mp", null, null, null)).hasMessageContaining("mode");
    }

    @Test
    void aCloseSaysWhichOwnersAreTheStudysRobots() {
        Close close = new Close(List.of(), List.of(), Map.of("good", "Good@Robots"));

        assertThat(close.isRobot("good@robots")).isTrue();
        assertThat(close.isRobot("a@uni.edu")).isFalse();
        assertThat(close.isRobot(null)).isFalse();
        assertThat(new Close(List.of(), null).robots()).isEmpty();
        assertThat(new Close(List.of(), List.of(), null).robots()).isEmpty();
    }
}
