package fm.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fm.Flexemarkets;
import fm.model.Holding;
import fm.model.OrderSide;
import fm.model.TickGrid;
import tools.jackson.databind.JsonNode;

/**
 * Every client call goes where {@code sdks/fixtures/routes/routes.json} says,
 * and nowhere else -- the API root above all.
 *
 * <p>See {@code sdks/fixtures/routes/README.md}. Python and TypeScript run the
 * same cases, so a route can only be wrong in all three at once, and then it is
 * wrong in the fixture where a reader can see it.
 */
class RouteFixturesTest {

    private static final Path ROUTES =
            Path.of("..", "..", "fixtures", "routes", "routes.json").toAbsolutePath().normalize();

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    @TempDir
    Path tmp;

    record Case(String call, JsonNode args, JsonNode requests, JsonNode returns, String why) {
        @Override
        public String toString() {
            return call + " " + args;
        }
    }

    static Stream<Case> cases() throws IOException {
        JsonNode doc = HttpFlexemarkets.MAPPER.readTree(Files.readString(ROUTES));
        return StreamSupport.stream(doc.path("cases").spliterator(), false)
                .map(c -> new Case(c.path("call").asString(), c.path("args"), c.path("requests"),
                                   c.has("returns") ? c.get("returns") : null, c.path("why").asString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theCallSendsExactlyTheRequestsListed(Case c) throws Exception {
        var expected = new ArrayList<JsonNode>();
        c.requests().forEach(expected::add);
        var mismatches = new ArrayList<String>();
        var served = new int[] {0};

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            if (path.startsWith("/api/tokens")) {
                _respond(exchange, 200, "application/json", """
                    {"token":"%s","person":{"id":7,"accountId":1,"email":"dev@dev","roles":["ROLE_USER"]},
                     "account":{"id":1,"name":"dev"}}""".formatted(TOKEN));
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (served[0] >= expected.size()) {
                mismatches.add("unlisted request " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
                _respond(exchange, 599, "text/plain", "");
                return;
            }
            JsonNode want = expected.get(served[0]++);
            _compare(want, exchange, body, mismatches);
            JsonNode response = want.path("response");
            JsonNode payload = response.get("body");
            String contentType = response.has("contentType") ? response.get("contentType").asString() : "application/json";
            String text = null == payload ? "" : payload.isString() ? payload.asString()
                        : HttpFlexemarkets.MAPPER.writeValueAsString(payload);
            _respond(exchange, response.path("status").asInt(200), contentType, text);
        });
        server.start();

        Object answer;
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/marketplaces/1";
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, endpoint, "route-fixtures-test")) {
            answer = _call(fm, c.call(), c.args());
        } finally {
            server.stop(0);
        }

        assertTrue(mismatches.isEmpty(), c.call() + ": " + String.join("; ", mismatches) + " -- " + c.why());
        assertEquals(expected.size(), served[0], c.call() + ": requests listed but never sent");
        if (null != c.returns()) {
            assertEquals(c.returns(), HttpFlexemarkets.MAPPER.valueToTree(answer), c.call() + " answered");
        }
    }

    @Test
    @DisplayName("every case's call is one this runner knows")
    void theFixtureIsNotEmpty() throws IOException {
        assertTrue(cases().count() >= 40, "only " + cases().count() + " route cases in " + ROUTES);
    }

    private static void _compare(JsonNode want, HttpExchange exchange, String body, List<String> mismatches) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String label = method + " " + exchange.getRequestURI();
        if (!want.path("method").asString().equals(method) || !want.path("path").asString().equals(path)) {
            mismatches.add("expected " + want.path("method").asString() + " " + want.path("path").asString()
                           + ", got " + label);
            return;
        }
        Map<String, String> query = _query(exchange.getRequestURI().getRawQuery());
        Map<String, String> wantQuery = new LinkedHashMap<>();
        want.path("query").properties().forEach(e -> wantQuery.put(e.getKey(), e.getValue().asString()));
        if (!wantQuery.equals(query)) {
            mismatches.add(label + ": query " + query + ", expected " + wantQuery);
        }
        _header(want, "contentType", exchange.getRequestHeaders().getFirst("Content-Type"), label, mismatches,
                actual -> actual.startsWith(want.path("contentType").asString()));
        _header(want, "accept", exchange.getRequestHeaders().getFirst("Accept"), label, mismatches,
                actual -> actual.contains(want.path("accept").asString()));
        JsonNode wantBody = want.get("body");
        if (null == wantBody) {
            return;
        }
        if (wantBody.isString()) {
            if (!wantBody.asString().equals(body)) {
                mismatches.add(label + ": body " + body + ", expected " + wantBody.asString());
            }
            return;
        }
        JsonNode sent = HttpFlexemarkets.MAPPER.readTree(body);
        if (wantBody.isObject()) {
            wantBody.properties().forEach(e -> {
                if (!e.getValue().equals(sent.get(e.getKey()))) {
                    mismatches.add(label + ": body." + e.getKey() + " = " + sent.get(e.getKey())
                                   + ", expected " + e.getValue());
                }
            });
        } else if (!wantBody.equals(sent)) {
            mismatches.add(label + ": body " + sent + ", expected " + wantBody);
        }
    }

    private static void _header(JsonNode want, String field, String actual, String label,
                                List<String> mismatches, Function<String, Boolean> matches) {
        if (want.has(field) && (null == actual || !matches.apply(actual))) {
            mismatches.add(label + ": " + field + " " + actual + ", expected " + want.get(field).asString());
        }
    }

    private static Map<String, String> _query(String raw) {
        var query = new LinkedHashMap<String, String>();
        if (null == raw || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            query.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static void _respond(HttpExchange exchange, int status, String contentType, String text)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (0 == bytes.length) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The fixture's named arguments onto the Java signature, overloads included. */
    private Object _call(Flexemarkets fm, String call, JsonNode a) throws IOException {
        long mp = a.path("marketplaceId").asLong();
        return switch (call) {
            case "marketplaces" -> fm.marketplaces();
            case "marketplace" -> fm.marketplace(mp);
            case "markets" -> fm.markets(mp);
            case "symbols" -> fm.symbols(mp);
            case "sessions" -> fm.sessions(mp);
            case "session" -> fm.session(mp);
            case "orders" -> a.has("sessionIds") ? fm.orders(mp, _longs(a.get("sessionIds")))
                           : a.has("symbol") ? fm.orders(mp, a.get("symbol").asString())
                           : fm.orders(mp);
            case "trades" -> fm.trades(mp, a.get("symbol").asString());
            case "activeOrders" -> fm.activeOrders(mp).body();
            case "recentTrades" -> a.has("size") ? fm.recentTrades(mp, a.get("size").asInt()).body()
                                                 : fm.recentTrades(mp).body();
            case "holdings" -> a.has("sessionIds") ? fm.holdings(mp, _longs(a.get("sessionIds"))) : fm.holdings(mp);
            case "holding" -> fm.holding(mp);
            case "connections" -> fm.connections(mp);
            case "identifiers" -> fm.identifiers(mp);
            case "users" -> fm.users();
            case "userById" -> fm.userById(a.get("userId").asLong());
            case "accountById" -> fm.accountById(a.get("accountId").asLong());
            case "accounts" -> fm.accounts();
            case "allotments" -> fm.allotments(mp, a.get("allocationId").asLong());
            case "downloadHoldings" -> a.has("sessionIds") ? fm.downloadHoldings(mp, _longs(a.get("sessionIds")))
                                     : fm.downloadHoldings(mp);
            case "submitLimit" -> fm.submitLimit(mp, a.get("marketId").asLong(),
                    OrderSide.valueOf(a.get("side").asString()), a.get("units").asLong(), a.get("price").asLong());
            case "submitCancel" -> fm.submitCancel(mp, a.get("marketId").asLong(), a.get("originalId").asLong());
            case "openSession" -> fm.openSession(mp);
            case "pauseSession" -> fm.pauseSession(mp);
            case "closeSession" -> fm.closeSession(mp);
            case "createMarketplaceFromJson" -> fm.createMarketplaceFromJson(a.get("json").asString());
            case "deleteMarketplace" -> { fm.deleteMarketplace(mp); yield null; }
            case "createMarket" -> fm.createMarket(mp, a.get("symbol").asString(), a.get("name").asString(),
                    _grid(a.get("price")), _grid(a.get("units")), a.get("privateMarket").asBoolean());
            case "allocate" -> fm.allocate(mp, _holdings(a.get("holdings")));
            case "uploadHoldings" -> fm.uploadHoldings(mp, _csv(a));
            case "uploadState" -> fm.uploadState(mp, _csv(a));
            case "removeWidget" -> a.has("userId")
                    ? fm.removeWidget(mp, a.get("key").asString(), a.get("userId").asLong())
                    : fm.removeWidget(mp, a.get("key").asString());
            case "allWidgets" -> fm.allWidgets(mp);
            case "signup" -> fm.signup(a.get("accountName").asString(), a.get("email").asString(),
                                       a.get("password").asString());
            case "approveAccount" -> fm.approveAccount(a.get("accountName").asString());
            case "deleteMyAccount" -> { fm.deleteMyAccount(); yield null; }
            case "deleteAccount" -> { fm.deleteAccount(a.get("accountId").asLong()); yield null; }
            case "createUser" -> fm.createUser(a.get("email").asString(), a.get("password").asString(),
                    a.get("firstName").asString(), a.get("lastName").asString(),
                    StreamSupport.stream(a.get("roles").spliterator(), false).map(JsonNode::asString)
                            .toArray(String[]::new));
            case "deleteUser" -> { fm.deleteUser(a.get("userId").asLong()); yield null; }
            case "managerOtpBundle" -> fm.managerOtpBundle(_longs(a.get("userIds")));
            default -> throw new AssertionError("no runner mapping for '" + call + "'");
        };
    }

    private static List<Long> _longs(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private static TickGrid _grid(JsonNode grid) {
        return new TickGrid(grid.get("minimum").asLong(), grid.get("maximum").asLong(), grid.get("tick").asLong());
    }

    private static List<Holding> _holdings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(h -> HttpFlexemarkets.MAPPER.treeToValue(h, Holding.class))
                .toList();
    }

    private Path _csv(JsonNode a) throws IOException {
        Path file = tmp.resolve("upload.csv");
        Files.writeString(file, a.get("csv").asString());
        return file;
    }
}
