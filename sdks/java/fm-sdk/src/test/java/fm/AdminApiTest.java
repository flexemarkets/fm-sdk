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

    /** When set, POST /api/accounts and DELETE /api/users/* answer 409. */
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

        server.createContext("/api/users", exchange -> {
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
        Types.Token created;
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
        List<Types.Account> all;
        Types.Account approved;
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
        Types.Person created;
        try (Flexemarkets fm = connect()) {
            created = fm.createUser("alice@lab.edu", "pw", "Alice", "Anderson", "ROLE_MANAGER");
        }

        assertThat(created.id()).isEqualTo(42L);
        var body = bodyOf("POST /api/users");
        assertThat(body).contains("\"email\":\"alice@lab.edu\"");
        assertThat(body).contains("\"roles\":[\"ROLE_MANAGER\"]");
    }

    /** No roles is an empty array, not a missing field or a null. */
    @Test
    void aUserCreatedWithoutRolesSendsAnEmptyArray() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.createUser("bob@lab.edu", "pw", "Bob", "Baker");
        }

        assertThat(bodyOf("POST /api/users")).contains("\"roles\":[]");
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

        assertThat(requests).contains("DELETE /api/users/42");
        assertThat(requests).contains("DELETE /api/accounts/2");
        assertThat(requests).contains("DELETE /api/marketplaces/5");
    }

    /** 204 with no body is success, not something to parse. */
    @Test
    void anEmptyDeleteResponseIsNotAParseFailure() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.deleteUser(42);
        }

        assertThat(requests).contains("DELETE /api/users/42");
    }

    // --- marketplaces -------------------------------------------------------

    @Test
    void aMarketplaceAndAMarketAreCreated() throws Exception {
        Types.Marketplace marketplace;
        Types.Market market;
        try (Flexemarkets fm = connect()) {
            marketplace = fm.createMarketplace("course", "class 2");
            market = fm.createMarket(5, "STK", "Stock", 0, 10_000, 1, false);
        }

        assertThat(marketplace.id()).isEqualTo(5L);
        assertThat(market.symbol()).isEqualTo("STK");

        assertThat(bodyOf("POST /api/marketplaces")).contains("\"description\":\"class 2\"");

        // Unit bounds are not parameters; they are sent fixed, and a market
        // created without them would reject every order as out of range.
        var marketBody = bodyOf("POST /api/marketplaces/5/markets");
        assertThat(marketBody).contains("\"unitMinimum\":1");
        assertThat(marketBody).contains("\"unitMaximum\":100");
        assertThat(marketBody).contains("\"unitTick\":1");
        assertThat(marketBody).contains("\"priceMaximum\":10000");
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
            assertThat(fm.account(2).name()).isEqualTo("acme");
            assertThat(fm.user(42).email()).isEqualTo("alice@lab.edu");
            assertThat(fm.identifiers(1)).containsExactly("t1", "t2");
        }

        assertThat(requests).contains("GET /api/accounts/2");
        assertThat(requests).contains("GET /api/users/42");
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
                    .isInstanceOf(Exceptions.AccountNameConflictException.class)
                    .satisfies(e -> {
                        var conflictException = (Exceptions.AccountNameConflictException) e;
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
                    .isInstanceOf(Exceptions.ConflictException.class)
                    .satisfies(e -> assertThat(((Exceptions.ConflictException) e).failure().suggestedName())
                            .as("the general handler reads the same suggestion as the specific one")
                            .isEqualTo("acme-2"));
        }
    }

    /** Deleting a user who still owns data is refused, and says whose. */
    @Test
    void deletingAUserWhoOwnsDataIsRefused() throws Exception {
        conflict = true;

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.deleteUser(42))
                    .isInstanceOf(Exceptions.PersonHasMarketplaceDataException.class)
                    .satisfies(e -> assertThat(
                            ((Exceptions.PersonHasMarketplaceDataException) e).userId()).isEqualTo(42L));
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
        Types.ManagerOtpBundle bundle;
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

    /** An implementation that cannot administer says so rather than pretending. */
    @Test
    void anImplementationWithoutAdministrationRefuses() {
        var readOnly = new Flexemarkets() {
            public Types.Account account() { return null; }
            public long accountId() { return 0; }
            public String accountName() { return null; }
            public Types.Person user() { return null; }
            public long userId() { return 0; }
            public Types.Token token() { return null; }
            public long endpointMarketplaceId() { return 0; }
            public String endpointUrl() { return null; }
            public List<Types.Marketplace> marketplaces() { return List.of(); }
            public Types.Marketplace marketplace(long id) { return null; }
            public List<Types.Market> markets(long id) { return List.of(); }
            public List<Types.Session> sessions(long id) { return List.of(); }
            public Types.Session session(long id) { return null; }
            public List<Types.Order> orders(long id) { return List.of(); }
            public List<Types.Holding> holdings(long id) { return List.of(); }
            public Types.Holding holding(long id) { return null; }
            public List<Types.ClientConnection> connections(long id) { return List.of(); }
            public List<Types.ClientConnection> connections(long id, List<Long> s) { return List.of(); }
            public List<String> symbols(long id) { return List.of(); }
            public List<Types.Session> sessions(long id, List<Long> s) { return List.of(); }
            public List<Types.Order> orders(long id, List<Long> s) { return List.of(); }
            public List<Types.Order> orders(long id, String symbol) { return List.of(); }
            public List<Types.Order> trades(long id, String symbol) { return List.of(); }
            public List<Types.Holding> holdings(long id, List<Long> s) { return List.of(); }
            public String downloadHoldings(long id) { return ""; }
            public String downloadHoldings(long id, List<Long> s) { return ""; }
            public List<Types.Allotment> allotments(long id, long allocationId) { return List.of(); }
            public List<Types.Person> users() { return List.of(); }
            public Types.Session openSession(long id) { return null; }
            public Types.Session pauseSession(long id) { return null; }
            public Types.Session closeSession(long id) { return null; }
            public Types.Marketplace createMarketplaceFromJson(String json) { return null; }
            public List<Types.Holding> allocate(long id, List<Types.Holding> h) { return List.of(); }
            public List<Types.Holding> uploadHoldings(long id, java.nio.file.Path csv) { return List.of(); }
            public Snapshot<List<Types.Order>> activeOrdersV1(long id) { return null; }
            public Snapshot<List<Types.Order>> recentTradesV1(long id, int size) { return null; }
            public Snapshot<List<Types.Order>> recentTradesV1(long id) { return null; }
            public Types.Order submitLimit(long a, long b, String c, long d, long e) { return null; }
            public Types.Order submitCancel(long a, long b, long c) { return null; }
            public Types.Order submitMarket(long a, long b, String c, long d) { return null; }
            public void listen(long id, java.util.concurrent.BlockingQueue<Object> q) {}
            public MarketView observe(long id) { return null; }
            public void reconnect() {}
            public void close() {}
        };

        assertThatThrownBy(() -> readOnly.deleteAccount(1))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("deleteAccount");
        assertThatThrownBy(() -> readOnly.managerOtpBundle(List.of(1L)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("managerOtpBundle");
    }
}
