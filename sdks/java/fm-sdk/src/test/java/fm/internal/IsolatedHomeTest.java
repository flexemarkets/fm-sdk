package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * A test never sees the ~/.fm of whoever runs it (the surefire configuration
 * in sdks/java/pom.xml). Fails on a developer machine that has ~/.fm if the
 * isolation is lost; on CI there is no ~/.fm to see either way.
 */
class IsolatedHomeTest {

    @Test
    void aTestRunsWithAnEmptyHomeAndNoFmOverrides() {
        var home = Path.of(System.getProperty("user.home"));
        assertThat(Files.exists(home.resolve(".fm"))).as("%s has a .fm", home).isFalse();
        assertThat(System.getenv("FM_API_URL")).isNull();
        assertThat(System.getenv("FM_URL")).isNull();
    }
}
