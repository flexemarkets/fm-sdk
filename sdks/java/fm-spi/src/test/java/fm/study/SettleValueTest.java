package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fm.manifest.ParameterSpec;

/**
 * Values the manager confirms at settle (contract 0.6, fm-ui
 * docs/EDITOR-AND-STUDY-REDESIGN.md §7.6): none unless a study names them.
 */
class SettleValueTest {

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
    void aStudyAsksNothingAtSettleUnlessItSaysSo() {
        assertThat(new Quiet().atSettle(Map.of())).isEmpty();

        SettleValue dividend = new SettleValue("dividend.STK", "STK's dividend", "paid per unit held at close",
                "dollars", "1.00");
        assertThat(dividend).extracting(SettleValue::key, SettleValue::defaultValue)
                .containsExactly("dividend.STK", "1.00");
        assertThatThrownBy(() -> new SettleValue("", "x", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettleValue("k", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'k'");
    }
}
