package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The administrative surface: creating accounts and users, approving them,
 * deleting them, and minting one-time passcodes.
 *
 * <p>Asserted against a real loopback server rather than a stubbed transport,
 * for the reason the management tests give: what matters is the request that
 * actually goes out — its verb, its path and the field names in its body — and
 * a stub would assert only that the client called itself the way this test
 * expected.
 *
 * <p>Several of these are destructive, so the verb is part of the contract: a
 * delete that went out as a POST would read as success here and leave the
 * thing standing.
 */
class AdminApiTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    /** Roles the sign-in token reports; a test may change this before connecting. */
    private String roles = "[\"ROLE_MANAGER\"]";

    /** When set, POST /api/accounts and DELETE /api/v1/users/* answer 409. */
    private boolean conflict = false;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/tokens", exchange ->
                respond(exchange, 200, """
                    {"token":"%s",
                     "person":{"id":7,"accountId":1,"email":"dev@dev","roles":%s},
                     "account":{"id":1,"name":"dev"}}
                    """.formatted(TOKEN, roles)));

        server.createContext("/api/accounts", exchange -> {
            record(exchange);
            if ("POST".equals(exchange.getRequestMethod())) {
                if (conflict) {
                    respond(exchange, 409,
                            "{\"status\":\"CONFLICT\",\"message\":\"taken\",\"suggestedName\":\"acme-2\"}");
                    return;
                }
                respond(exchange, 200, """
                    {"token":"%s",
                     "person":{"id":8,"accountId":2,"email":"owner@new"},
                     "account":{"id":2,"name":"acme"}}
                    """.formatted(TOKEN));
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, 204, "");
            } else if (exchange.getRequestURI().getPath().matches(".*/accounts/\\d+")) {
                respond(exchange, 200, "{\"id\":2,\"name\":\"acme\",\"approval\":true}");
            } else {
                respond(exchange, 200,
                        "[{\"id\":1,\"name\":\"dev\"},{\"id\":2,\"name\":\"acme\"}]");
            }
        });

        server.createContext("/api/approvals", exchange -> {
            record(exchange);
            respond(exchange, 200,
                    "{\"account\":{\"id\":2,\"name\":\"acme\",\"approval\":true},\"approve\":true}");
        });

        server.createContext("/api/v1/users", exchange -> {
            record(exchange);
            if ("DELETE".equals(exchange.getRequestMethod())) {
                if (conflict) {
                    respond(exchange, 409, "{\"message\":\"user still owns orders\"}");
                    return;
                }
                respond(exchange, 204, "");
            } else {
                respond(exchange, 200,
                        "{\"id\":42,\"accountId\":1,\"email\":\"alice@lab.edu\"}");
            }
        });

        server.createContext("/api/marketplaces", exchange -> {
            record(exchange);
            if ("DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, 204, "");
            } else if (exchange.getRequestURI().getPath().endsWith("/markets")) {
                respond(exchange, 200,
                        "{\"id\":10,\"marketplaceId\":5,\"symbol\":\"STK\",\"unitTick\":1}");
            } else {
                respond(exchange, 200, "{\"id\":5,\"name\":\"course\",\"markets\":[]}");
            }
        });

        server.createContext("/api/marketplaces/1/privateTraders", exchange -> {
            record(exchange);
            respond(exchange, 200, "[\"t1\",\"t2\"]");
        });

        server.createContext("/api/accounts/me", exchange -> {
            record(exchange);
            respond(exchange, 204, "");
        });

        server.createContext("/api/marketplaces/1/symbols", exchange -> {
            record(exchange);
            respond(exchange, 200, "[\"STK\",\"BND\"]");
        });

        server.createContext("/api/otp/manager", exchange -> {
            record(exchange);
            respond(exchange, 200, """
                {"expiresAt":"2026-08-15T18:00:00Z",
                 "otps":[{"userId":1,"email":"alice@lab.edu","otp":"123456"}]}
                """);
        });

        server.createContext("/api", exchange -> {
            record(exchange);
            respond(exchange, 200, """
                {"_links":{"marketplaces":{"href":"%1$s/marketplaces"},
                           "accounts":{"href":"%1$s/accounts"},
                           "users":{"href":"%1$s/users"},
                           "usersJson":{"href":"%1$s/usersJson"}}}
                """.formatted(api()));
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    private Flexemarkets connect() throws IOException {
        return Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "admin-test");
    }

    private void record(HttpExchange exchange) {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        try {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            bodies.add("");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private String bodyOf(String requestPrefix) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).startsWith(requestPrefix)) {
                return bodies.get(i);
            }
        }
        throw new AssertionError("no request matching " + requestPrefix + " in " + requests);
    }

    // --- accounts -----------------------------------------------------------

    /**
     * The owner's credentials go out under ownerEmail/ownerPassword, not
     * email/password. Send the wrong names and fm-server creates the account
     * with no owner it can sign in as.
     */
    @Test
    void signupNamesTheOwnersCredentialsTheWayTheServerReadsThem() throws Exception {
        Token created;
        try (Flexemarkets fm = connect()) {
            created = fm.signup("acme", "owner@new", "s3cret", "Ada", "Lovelace");
        }

        assertThat(created.account().name()).isEqualTo("acme");

        var body = bodyOf("POST /api/accounts");
        assertThat(body).contains("\"ownerEmail\":\"owner@new\"");
        assertThat(body).contains("\"ownerPassword\":\"s3cret\"");
        assertThat(body).contains("\"accountName\":\"acme\"");
        assertThat(body).doesNotContain("\"email\":");
    }

    /** The short form is the long one with no name, not a different request. */
    @Test
    void theShortSignupSendsTheSameShape() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.signup("acme", "owner@new", "s3cret");
        }

        var body = bodyOf("POST /api/accounts");
        assertThat(body).contains("\"accountName\":\"acme\"");
        assertThat(body).contains("\"firstName\":null");
    }

    @Test
    void accountsAreListedAndApproved() throws Exception {
        List<Account> all;
        Account approved;
        try (Flexemarkets fm = connect()) {
            all = fm.accounts();
            approved = fm.approveAccount("acme");
        }

        assertThat(all).hasSize(2);
        assertThat(approved.name()).isEqualTo("acme");
        assertThat(approved.approval()).isTrue();
        assertThat(bodyOf("POST /api/approvals")).contains("\"name\":\"acme\"").contains("\"approval\":true");
    }

    // --- users --------------------------------------------------------------

    @Test
    void aUserIsCreatedWithTheRolesGiven() throws Exception {
        Person created;
        try (Flexemarkets fm = connect()) {
            created = fm.createUser("alice@lab.edu", "pw", "Alice", "Anderson", "ROLE_MANAGER");
        }

        assertThat(created.id()).isEqualTo(42L);
        var body = bodyOf("POST /api/v1/users");
        assertThat(body).contains("\"email\":\"alice@lab.edu\"");
        assertThat(body).contains("\"roles\":[\"ROLE_MANAGER\"]");
    }

    /** No roles is an empty array, not a missing field or a null. */
    @Test
    void aUserCreatedWithoutRolesSendsAnEmptyArray() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.createUser("bob@lab.edu", "pw", "Bob", "Baker");
        }

        assertThat(bodyOf("POST /api/v1/users")).contains("\"roles\":[]");
    }

    // --- deletion -----------------------------------------------------------

    /**
     * Deletes go out as DELETE. The verb is the whole request here: a POST to
     * the same path creates or updates, and would answer 2xx while leaving the
     * user, account or marketplace exactly where it was.
     */
    @Test
    void deletesUseTheDeleteVerb() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.deleteUser(42);
            fm.deleteAccount(2);
            fm.deleteMarketplace(5);
        }

        assertThat(requests).contains("DELETE /api/v1/users/42");
        assertThat(requests).contains("DELETE /api/accounts/2");
        assertThat(requests).contains("DELETE /api/marketplaces/5");
    }

    /** 204 with no body is success, not something to parse. */
    @Test
    void anEmptyDeleteResponseIsNotAParseFailure() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.deleteUser(42);
        }

        assertThat(requests).contains("DELETE /api/v1/users/42");
    }

    // --- marketplaces -------------------------------------------------------

    /**
     * A market is added to a marketplace that already exists.
     *
     * <p>This used to create the marketplace here too, with
     * {@code createMarketplace("course", "class 2")}, and assert the POST
     * carried the description. It did -- and the server answered
     * {@code MARKETPLACE_INVALID: At least one market is required}, because
     * that method sent no markets and there is no way to give it any. The test
     * asserted the request and never the response, so it passed for as long as
     * the method existed. Marketplaces are made with
     * createMarketplaceFromJson, which is what every study already used.
     */
    @Test
    void aMarketIsAddedToAMarketplace() throws Exception {
        Market market;
        try (Flexemarkets fm = connect()) {
            market = fm.createMarket(5, "STK", "Stock",
                    new TickGrid(0, 10_000, 1), TickGrid.units(), false);
        }

        assertThat(market.symbol()).isEqualTo("STK");

        var marketBody = bodyOf("POST /api/marketplaces/5/markets");
        assertThat(marketBody).contains("\"priceMinimum\":0");
        assertThat(marketBody).contains("\"priceMaximum\":10000");
        assertThat(marketBody).contains("\"unitMinimum\":1");
        assertThat(marketBody).contains("\"unitMaximum\":100");
        assertThat(marketBody).contains("\"unitTick\":1");
    }

    /**
     * The defect: unit bounds were fixed at 1/100/1 with no way to say
     * otherwise, on a call that set the price grid three arguments earlier.
     * The server enforces the two identically -- an order is refused for
     * "units is not on a tic" exactly as for a price -- so a market needing
     * lots of ten could not be made here at all.
     */
    @Test
    void unitBoundsAreTheCallersToo() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.createMarket(5, "STK", "Stock",
                    new TickGrid(100, 200, 25), new TickGrid(10, 500, 10), false);
        }

        var body = bodyOf("POST /api/marketplaces/5/markets");
        assertThat(body).contains("\"unitMinimum\":10");
        assertThat(body).contains("\"unitMaximum\":500");
        assertThat(body).contains("\"unitTick\":10");
    }

    /** Both grids round by the rule the server checks. */
    @Test
    void aGridRoundsOnItsOwn() {
        var units = new TickGrid(10, 500, 10);

        assertThat(units.round(37)).isEqualTo(30L);
        assertThat(units.round(1)).isEqualTo(10L);
        assertThat(TickGrid.units().round(1_000)).isEqualTo(100L);
    }

    @Test
    void symbolsAreReadFromTheMarketplace() throws Exception {
        List<String> symbols;
        try (Flexemarkets fm = connect()) {
            symbols = fm.symbols(1);
        }

        assertThat(symbols).containsExactly("STK", "BND");
    }

    @Test
    void singleAccountsUsersAndIdentifiersAreReadById() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThat(fm.accountById(2).name()).isEqualTo("acme");
            assertThat(fm.userById(42).email()).isEqualTo("alice@lab.edu");
            assertThat(fm.identifiers(1)).containsExactly("t1", "t2");
        }

        assertThat(requests).contains("GET /api/accounts/2");
        assertThat(requests).contains("GET /api/v1/users/42");
    }

    /** Deleting your own account is its own route, not accounts/{yourId}. */
    @Test
    void deletingYourOwnAccountUsesTheMeRoute() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.deleteMyAccount();
        }

        assertThat(requests).contains("DELETE /api/accounts/me");
    }

    /**
     * A taken name arrives with the server's suggestion, and is raised as its
     * own type so a caller can offer it rather than digging it back out of a
     * generic conflict.
     */
    @Test
    void aTakenAccountNameCarriesTheServersSuggestion() throws Exception {
        conflict = true;

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.signup("acme", "owner@new", "s3cret"))
                    .isInstanceOf(AccountNameConflictException.class)
                    .satisfies(e -> {
                        var conflictException = (AccountNameConflictException) e;
                        assertThat(conflictException.requestedName()).isEqualTo("acme");
                        assertThat(conflictException.suggestedName()).isEqualTo("acme-2");
                    });
        }
    }

    /**
     * A caller who handles conflicts generally catches this one too.
     *
     * <p>The javadoc on {@code AccountNameConflictException} claimed as much
     * while the class extended {@code FlexemarketsException} directly, so
     * {@code catch (ConflictException)} let through exactly the conflict the
     * SDK went to the trouble of describing.
     */
    @Test
    void aTakenAccountNameIsCatchableAsAConflict() throws Exception {
        conflict = true;

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.signup("acme", "owner@new", "s3cret"))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(e -> assertThat(((ConflictException) e).failure().suggestedName())
                            .as("the general handler reads the same suggestion as the specific one")
                            .isEqualTo("acme-2"));
        }
    }

    /**
     * And is catchable as a conflict, because that is what the server answered.
     *
     * <p>It extended FlexemarketsException directly until 0.1.0, which left the
     * three SDKs disagreeing: Python and TypeScript both made it a conflict.
     */
    @Test
    void aUserWhoOwnsDataIsCatchableAsAConflict() throws Exception {
        conflict = true;

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.deleteUser(42))
                    .isInstanceOf(ConflictException.class)
                    .isInstanceOf(PersonHasMarketplaceDataException.class);
        }
    }

    /** Deleting a user who still owns data is refused, and says whose. */
    @Test
    void deletingAUserWhoOwnsDataIsRefused() throws Exception {
        conflict = true;

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.deleteUser(42))
                    .isInstanceOf(PersonHasMarketplaceDataException.class)
                    .satisfies(e -> assertThat(
                            ((PersonHasMarketplaceDataException) e).userId()).isEqualTo(42L));
        }
    }

    // --- identity -----------------------------------------------------------

    /**
     * isAdmin reads the roles on the sign-in token. A token that carries none
     * is not an admin — the alternative, treating absent as unknown and
     * guessing, would have a caller offering administrative options to someone
     * the server will refuse.
     */
    @Test
    void isAdminReadsTheRolesOnTheToken() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThat(fm.isAdmin()).as("ROLE_MANAGER is not ROLE_ADMIN").isFalse();
            assertThat(fm.token().token()).isEqualTo(TOKEN);
        }

        stopServer();
        roles = "[\"ROLE_ADMIN\",\"ROLE_MANAGER\"]";
        startServer();

        try (Flexemarkets fm = connect()) {
            assertThat(fm.isAdmin()).isTrue();
        }
    }

    @Test
    void aTokenWithoutRolesIsNotAdmin() throws Exception {
        stopServer();
        roles = "null";
        startServer();

        try (Flexemarkets fm = connect()) {
            assertThat(fm.isAdmin()).isFalse();
            assertThat(fm.isManager()).isFalse();
            assertThat(fm.hasRole("ROLE_USER")).isFalse();
        }
    }

    /**
     * The role that runs a study, and the general form behind both names.
     *
     * <p>Python has answered {@code is_manager()} and {@code has_role()} since
     * the management surface landed; Java answered neither, so a study asking
     * whether it could open a session had to read {@code user().roles()} and
     * compare strings the SDK already knows how to compare.
     */
    @Test
    void managerAndArbitraryRolesAreAnswerableToo() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThat(fm.isManager()).isTrue();
            assertThat(fm.hasRole("ROLE_MANAGER")).isTrue();
            assertThat(fm.hasRole("ROLE_ADMIN")).isFalse();
            assertThat(fm.hasRole(null)).as("no role is not every role").isFalse();
        }
    }

    // --- one-time passcodes -------------------------------------------------

    @Test
    void otpBundlesAreMintedForTheUsersAsked() throws Exception {
        ManagerOtpBundle bundle;
        try (Flexemarkets fm = connect()) {
            bundle = fm.managerOtpBundle(List.of(1L, 2L));
        }

        assertThat(bundle.expiresAt()).isEqualTo("2026-08-15T18:00:00Z");
        assertThat(bundle.otps()).singleElement().satisfies(entry -> {
            assertThat(entry.userId()).isEqualTo(1L);
            assertThat(entry.otp()).isEqualTo("123456");
        });
        assertThat(bodyOf("POST /api/otp/manager")).contains("\"userIds\":[1,2]");
    }

    /**
     * What the roles buy: a trading client is not an administrator, and the
     * type is where that is said.
     *
     * <p>This used to build a fake implementing the whole interface and assert
     * that deleteAccount and managerOtpBundle threw at runtime, because they
     * were defaults. There are none now. Something that only trades implements
     * {@link Writing}, the compiler refuses the administrative call, and no
     * test can reach the exception that used to be worth asserting.
     *
     * <p>Three methods, where the old fake needed sixty. That is the same fact
     * from the other side: a fake now models what it stands in for.
     */
    @Test
    void aTradingClientIsNotAnAdministrator() {
        Writing trader = new Writing() {
            public Order submitLimit(long m, long k, Side s, long u, long p) { return null; }
            public Order submitCancel(long m, long k, long o) { return null; }
            public Order submitMarket(long m, long k, Side s, long u) { return null; }
        };

        assertThat(trader.submitCancel(1, 1, 1)).isNull();
        assertThat(trader)
                .isNotInstanceOf(Administration.class)
                .isNotInstanceOf(Flexemarkets.class);
    }
}
