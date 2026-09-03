package fm;

import fm.model.Market;
import fm.model.Marketplace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Live-server smoke test for the Java SDK. Catches catastrophic protocol
 * regressions (V0/V1 endpoint path drift, snapshot header format, WS
 * subscribe destination, etc.) without needing the heavy in-process
 * Spring Boot setup the studies use.
 *
 * <p>Opt-in via two preconditions, both checked by {@link #liveServerReady()}:
 * <ol>
 *   <li>{@code ~/.fm/credential} and {@code ~/.fm/endpoint} both exist —
 *       the same config layout the SDK examples use.</li>
 *   <li>The {@code FM_LIVE_TESTS=1} environment variable is set. Without
 *       it the tests self-skip so CI doesn't false-fail on a missing
 *       server.</li>
 * </ol>
 *
 * <p>The smoke test deliberately stays read-only: it picks the
 * endpoint's marketplace, opens a {@link Desk}, verifies the
 * accessors don't throw, and closes. No orders submitted; no
 * marketplaces created. That keeps the test cheap and idempotent —
 * running it 100 times in a row leaves zero residue on the server.
 */
public class FlexemarketsLiveServerTest {

    private static Path _credentialPath;
    private static Path _endpointPath;

    @BeforeAll
    static void resolveConfig() {
        var home = System.getProperty("user.home");
        _credentialPath = Paths.get(home, ".fm", "credential");
        _endpointPath = Paths.get(home, ".fm", "endpoint");
    }

    /** Precondition gate for every test in this class. Returns true only
     *  when both config files exist AND {@code FM_LIVE_TESTS=1} is set
     *  in the environment. Without that env var the tests skip silently
     *  so CI doesn't false-fail. */
    static boolean liveServerReady() {
        if (!"1".equals(System.getenv("FM_LIVE_TESTS"))) return false;
        var home = System.getProperty("user.home");
        return Files.exists(Paths.get(home, ".fm", "credential"))
            && Files.exists(Paths.get(home, ".fm", "endpoint"));
    }

    @Test
    @EnabledIf("liveServerReady")
    void connectsToLocalServerAndListsMarketplaces() throws Exception {
        try (var fm = _connect("fm-sdk-live-test/connectAndList")) {
            List<Marketplace> marketplaces = fm.marketplaces();
            // The endpoint is configured to a specific marketplace, so
            // marketplaces() returns at least that one even if the
            // account has nothing else.
            assertThat(marketplaces).isNotEmpty();
        }
    }

    @Test
    @EnabledIf("liveServerReady")
    void opensADeskOnTheEndpointMarketplaceWithoutThrowing() throws Exception {
        try (var fm = _connect("fm-sdk-live-test/desk")) {
            long marketplaceId = fm.endpointMarketplaceId();
            // Use try-with-resources for the handle so the refcount
            // unwinds even if an assertion fails mid-test.
            try (Desk desk = (Desk) fm.desk(marketplaceId)) {
                assertThat(desk.marketplaceId()).isEqualTo(marketplaceId);
                assertThat(desk.markets()).isNotEmpty();
                for (Market m : desk.markets()) {
                    Book book = desk.book(m.id());
                    assertThat(book).isNotNull();
                    // bestBuyPrice / bestSellPrice return -1 when empty;
                    // either way they shouldn't throw, which is the real
                    // smoke-test signal.
                    assertThatNoException().isThrownBy(book::bestBuyPrice);
                    assertThatNoException().isThrownBy(book::bestSellPrice);
                }
            }
        }
    }

    @Test
    @EnabledIf("liveServerReady")
    void sharesOneDeskAcrossRepeatCallsForSameMarketplace() throws Exception {
        try (var fm = _connect("fm-sdk-live-test/sharedDesk")) {
            long marketplaceId = fm.endpointMarketplaceId();
            Desk a = fm.desk(marketplaceId);
            Desk b = fm.desk(marketplaceId);
            try {
                // Both handles see the same marketplace + market list —
                // the contract isn't that they're equal references, it's
                // that they expose the same state coherently.
                assertThat(a.marketplaceId()).isEqualTo(b.marketplaceId());
                assertThat(a.markets()).usingRecursiveComparison().isEqualTo(b.markets());
                // Closing one handle must NOT close the shared desk —
                // 'b' should still be usable.
                a.close();
                assertThatNoException().isThrownBy(b::markets);
            } finally {
                b.close();
            }
        }
    }

    @Test
    @EnabledIf("liveServerReady")
    void closingFlexemarketsForceClosesRemainingShared() throws Exception {
        Flexemarkets fm = _connect("fm-sdk-live-test/forceClose");
        long marketplaceId = fm.endpointMarketplaceId();
        Desk desk = fm.desk(marketplaceId);
        // Intentionally don't close 'desk' — Flexemarkets.close() must
        // sweep it up so the WS subscription is released.
        assertThatNoException().isThrownBy(fm::close);
    }

    private static Flexemarkets _connect(String clientDescription) throws IOException {
        // Flexemarkets.connect expects file paths (or a token / URL),
        // not the file contents. Same convention the ticker example uses.
        return Flexemarkets.connect(
                _credentialPath.toString(),
                _endpointPath.toString(),
                clientDescription);
    }
}
