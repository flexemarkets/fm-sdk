package fm;

import fm.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server that cannot be reached says so, and says where it was told to look.
 *
 * <p>Connecting begins with a sign-in, so a stale {@code ~/.fm/endpoint} —
 * pointing at a local server that was not running — failed every command with
 * "Sign-in request failed". That names the wrong thing entirely: the
 * credential was fine, and the message mentioned neither the address dialled
 * nor which of the four places it came from. The obvious next move was to
 * check the password, which is the one thing that could not have been wrong.
 */
class UnreachableEndpointTest {

    @Test
    void connectionRefusedNamesTheAddressAndNotTheSignIn() {
        var endpoint = "http://127.0.0.1:" + closedPort() + "/api";

        assertThatThrownBy(() -> Flexemarkets.connect(token(), endpoint, "fm-unreachable-test"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Cannot reach the server at http://127.0.0.1:")
            .hasMessageContaining("refused or unreachable")
            .hasMessageContaining("-E/--endpoint")
            .hasMessageNotContainingAny("Sign-in", "sign-in");
    }

    /**
     * A misspelled host is a different fault from a host that will not answer,
     * and the JDK hands both over as {@code ConnectException} with no message.
     * Only the root cause tells them apart.
     */
    @Test
    void aHostThatDoesNotResolveIsNamedAsSuch() {
        // .invalid is reserved precisely so that it never resolves (RFC 2606).
        var endpoint = "https://api.flexemarkets.invalid/api";

        assertThatThrownBy(() -> Flexemarkets.connect(token(), endpoint, "fm-unreachable-test"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Cannot reach the server at https://api.flexemarkets.invalid")
            .hasMessageContaining("unknown host");
    }

    /** The address someone did not type is the one they most need traced. */
    @Test
    void anEndpointReadFromAFileNamesTheFile(@TempDir Path directory) throws IOException {
        var file = directory.resolve("endpoint");
        Files.writeString(file, "endpoint=http://127.0.0.1:" + closedPort() + "/api\n");

        assertThatThrownBy(() -> Flexemarkets.connect(token(), file.toString(), "fm-unreachable-test"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("That address came from " + file);
    }

    /**
     * A token, not an account and password: the credential must play no part
     * in a failure that never reached the server, and this way there is none
     * to read from the machine running the test.
     */
    private static String token() {
        return "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
    }

    /** A port that was free a moment ago, and so has nothing listening on it. */
    private static int closedPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port to close", e);
        }
    }
}
