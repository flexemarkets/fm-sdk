package fm.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The words a manifest is written in. A robot's manifest is JSON from another
 * repository, read here by name: a type that does not read back as itself, or
 * an unknown word read as some default, launches a robot with the wrong
 * command line.
 */
class ManifestVocabularyTest {

    @Test
    void everyParameterTypeReadsBackAsItself_andAnUnknownOneIsRefused() {
        Arrays.stream(ParameterType.values())
                .forEach(type -> assertThat(ParameterType.of(type.json())).isSameAs(type));
        assertThatThrownBy(() -> ParameterType.of("positional-quads")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParameterType.of("POSITIONAL")).as("the wire spelling only")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGroupedTypeRepeatsInGroupsOfItsSize() {
        assertThat(ParameterType.POSITIONAL.groupSize()).isEqualTo(1);
        assertThat(ParameterType.POSITIONAL.repeating()).isFalse();
        assertThat(ParameterType.OPTION.repeating()).isFalse();
        assertThat(ParameterType.POSITIONAL_PAIRS.groupSize()).isEqualTo(2);
        assertThat(ParameterType.POSITIONAL_TRIPLES.groupSize()).isEqualTo(3);
        assertThat(ParameterType.POSITIONAL_TRIPLES.repeating()).isTrue();
    }

    @Test
    void everyValueTypeReadsBackAsItself_andAnUnknownOneIsRefused() {
        Arrays.stream(ValueType.values())
                .forEach(type -> assertThat(ValueType.of(type.json())).isSameAs(type));
        assertThatThrownBy(() -> ValueType.of("decimal")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValueIsAcceptedOnlyInItsTypesSpelling() {
        assertThat(ValueType.INTEGER.accepts(" 12 ")).isTrue();
        assertThat(ValueType.INTEGER.accepts("1.5")).isFalse();
        assertThat(ValueType.INTEGER.accepts("twelve")).isFalse();
        assertThat(ValueType.FLOAT.accepts("0.03")).isTrue();
        assertThat(ValueType.FLOAT.accepts("three percent")).isFalse();
        assertThat(ValueType.BOOLEAN.accepts("TRUE")).isTrue();
        assertThat(ValueType.BOOLEAN.accepts(" false ")).isTrue();
        assertThat(ValueType.BOOLEAN.accepts("yes")).isFalse();
        assertThat(ValueType.STRING.accepts("anything at all")).isTrue();
        Arrays.stream(ValueType.values())
                .forEach(type -> assertThat(type.accepts(null)).as("%s and no value", type).isFalse());
    }
}
