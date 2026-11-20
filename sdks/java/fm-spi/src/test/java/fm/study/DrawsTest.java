package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

/**
 * A run's draws are made once (contract 0.6, fm-ui
 * docs/EDITOR-AND-STUDY-REDESIGN.md §7.5): from the seed a host keeps for the
 * run, the same numbers each time, so a previewed settle pays what it showed.
 */
class DrawsTest {

    @Test
    void aDrawFromTheRunsSeedIsTheSameEachTimeItIsAsked() {
        Map<String, String> run = Map.of(Draws.SEED, "424242", "duration", "5");

        assertThat(sample(Draws.of(run, "dividend", 3))).as("a preview and its commit")
                .isEqualTo(sample(Draws.of(new HashMap<>(run), "dividend", 3)));
        assertThat(sample(Draws.of(run, "dividend", 3))).as("another rotation")
                .isNotEqualTo(sample(Draws.of(run, "dividend", 4)));
        assertThat(sample(Draws.of(run, "dividend", 3))).as("another purpose")
                .isNotEqualTo(sample(Draws.of(run, "stop", 3)));
        assertThat(sample(Draws.of(run, "dividend", 3))).as("another run")
                .isNotEqualTo(sample(Draws.of(Map.of(Draws.SEED, "424243"), "dividend", 3)));
    }

    @Test
    void withoutASeedEachDrawIsFresh_asOnTheCommandLine() {
        assertThat(sample(Draws.of(Map.of(), "dividend", 1))).isNotEqualTo(sample(Draws.of(Map.of(), "dividend", 1)));
        assertThat(sample(Draws.of(null, "dividend", 1))).hasSize(8);
        assertThatThrownBy(() -> Draws.of(Map.of(Draws.SEED, "lucky"), "dividend", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(Draws.SEED);
    }

    private static List<Long> sample(RandomGenerator random) {
        List<Long> numbers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            numbers.add(random.nextLong());
        }
        return numbers;
    }
}
