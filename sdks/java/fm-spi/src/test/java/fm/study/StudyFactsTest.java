package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fm.manifest.ParameterSpec;
import fm.study.StudyFacts.LateJoiners;
import fm.study.StudyFacts.Owner;

/**
 * A study's facts (contract 0.6): who owns the settings it writes, and how a
 * run of it is shown and joined. A study that says nothing answers
 * {@link StudyFacts#NONE}, which leaves every setting the manager's -- the
 * behaviour of every study before 0.6 -- so none has to change to keep working.
 */
class StudyFactsTest {

    /** A study that says nothing beyond its plan. */
    private static final class Quiet implements StudyProvider {
        @Override public String id() { return "quiet"; }
        @Override public String name() { return "Quiet"; }
        @Override public String description() { return ""; }
        @Override public List<ParameterSpec> parameters() { return List.of(); }
        @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aStudyThatSaysNothingLeavesEverySettingTheManagers() {
        StudyFacts facts = new Quiet().facts(Map.of());

        assertThat(facts).isSameAs(StudyFacts.NONE);
        assertThat(facts.owned()).isEmpty();
        assertThat(facts.owner(StudyFacts.config("fm.marketplace.session.duration"))).isNull();
        assertThat(facts.marketsFixed()).isFalse();
        assertThat(facts.robotsOpen()).isTrue();
        assertThat(facts.hidesTotal()).isFalse();
        assertThat(facts.lateJoiners()).isEqualTo(LateJoiners.NEXT_ROTATION);
        assertThat(facts.managerHolds()).isFalse();
        assertThat(facts.showsRank()).isTrue();
    }

    @Test
    void settingsAreNamedByWhatTheyAre_andOwnedAsTheStudySays() {
        Map<String, Owner> owned = new HashMap<>();
        owned.put(StudyFacts.market("STK", "priceTick"), Owner.LOCKED);
        owned.put(StudyFacts.config("fm.marketplace.session.duration"), Owner.STUDY);
        owned.put(StudyFacts.robot("local:fm-maker", "interval"), Owner.LOCKED);
        StudyFacts facts = new StudyFacts(owned, true, false, true, LateJoiners.NONE, true, false);
        owned.clear();

        assertThat(facts.owned()).as("its own copy").hasSize(3);
        assertThat(facts.owner("market:STK:priceTick")).isEqualTo(Owner.LOCKED);
        assertThat(facts.owner("config:fm.marketplace.session.duration")).isEqualTo(Owner.STUDY);
        assertThat(facts.owner("robot:local:fm-maker:interval")).isEqualTo(Owner.LOCKED);
        assertThat(facts.owner("market:STK:name")).isNull();
    }

    @Test
    void aKeyThatNamesNoSettingIsRefusedWhenTheFactsAreMade() {
        assertThatThrownBy(() -> new StudyFacts(Map.of("fm.marketplace.session.duration", Owner.STUDY),
                false, true, false, LateJoiners.NONE, false, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("names no setting");
        assertThatThrownBy(() -> new StudyFacts(Map.of(), false, true, false, null, false, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("join late");
    }

    @Test
    void theContractIsAtSixAndAProviderSaysSo() {
        assertThat(StudyProvider.STUDY_SPI_VERSION).isEqualTo("0.6");
        assertThat(new Quiet().studySpiVersion()).isEqualTo("0.6");
    }
}
