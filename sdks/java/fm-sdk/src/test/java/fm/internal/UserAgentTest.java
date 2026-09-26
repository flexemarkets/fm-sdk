package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The User-Agent names the version built (fm-server#1012). It was the
 * constant "fm-sdk-java/0.1.0" from 0.1.0 to 0.3.3, so the server could not
 * tell one release from another.
 */
class UserAgentTest {

    @Test
    void namesTheVersionBeingBuilt() throws Exception {
        // VERSION at the repo root is what `make set-version` fans out to the
        // pom; the filtered resource carries the pom's. A SNAPSHOT build reads
        // x.y.z-SNAPSHOT, which the server still cuts to x.y.
        String released = Files.readString(Path.of("../../../VERSION")).trim();

        assertThat(HttpFlexemarkets.FM_SDK_CLIENT)
            .startsWith("fm-sdk-java/" + released)
            .doesNotContain("${");
    }
}
