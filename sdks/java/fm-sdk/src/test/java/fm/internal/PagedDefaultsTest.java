package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fm.Flexemarkets;
import fm.Page;
import fm.model.Account;
import fm.model.ClientConnection;
import fm.model.MarketCall;
import fm.model.Marketplace;
import fm.model.Order;
import fm.model.Person;
import fm.model.Session;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An implementation that reads only whole collections -- fm-server's
 * in-process client, a test fake -- pages through the roles' defaults, and each
 * default pages the read it is named after.
 *
 * <p>The client under test is a proxy: the seven whole-collection reads answer
 * three rows, every default method runs as written, and anything else fails.
 */
class PagedDefaultsTest {

    private static final Path ROUTES =
            Path.of("..", "..", "fixtures", "routes", "routes.json").toAbsolutePath().normalize();

    private static final long MP = 1;

    /** Each read's rows: ids 1, 2 and 3, from the row its route case answers. */
    private static Map<String, List<?>> _reads;

    @BeforeAll
    static void readTheRows() throws IOException {
        JsonNode cases = HttpFlexemarkets.MAPPER.readTree(Files.readString(ROUTES)).path("cases");
        _reads = Map.of(
                "marketplaces", _rows(cases, "marketplaces", Marketplace.class),
                "sessions", _rows(cases, "sessions", Session.class),
                "orders", _rows(cases, "orders", Order.class),
                "users", _rows(cases, "users", Person.class),
                "connections", _rows(cases, "connections", ClientConnection.class),
                "accounts", _rows(cases, "accounts", Account.class),
                "calls", _rows(cases, "calls", MarketCall.class));
    }

    @Test
    void everyPagedReadDefaultsToCuttingItsWholeRead() {
        Flexemarkets fm = _wholeReadsOnly();

        _assertNewestTwo(fm.marketplacesPage(2), Marketplace::id);
        _assertNewestTwo(fm.sessionsPage(MP, 2), Session::id);
        _assertNewestTwo(fm.ordersPage(MP, 2), Order::id);
        _assertNewestTwo(fm.usersPage(2), Person::id);
        _assertNewestTwo(fm.connectionsPage(MP, 2), ClientConnection::connectionId);
        _assertNewestTwo(fm.accountsPage(2), Account::id);
        _assertNewestTwo(fm.callsPage(MP, 2), MarketCall::id);
    }

    @Test
    void beforeReachesTheDefault() {
        Flexemarkets fm = _wholeReadsOnly();

        assertThat(fm.sessionsPage(MP, 2, 2L).rows()).extracting(Session::id).containsExactly(1L);
        assertThat(fm.sessionsPage(MP, 2, 2L).next()).isNull();
    }

    private static <T> void _assertNewestTwo(Page<T> page, Function<T, Long> id) {
        assertThat(page.rows()).extracting(id).containsExactly(2L, 3L);
        assertThat(page.next()).isEqualTo(2L);
    }

    private static Flexemarkets _wholeReadsOnly() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            List<?> rows = _reads.get(method.getName());
            if (null != rows && method.getReturnType() == List.class
                    && (null == args || 1 == args.length && Long.valueOf(MP).equals(args[0]))) {
                return rows;
            }
            throw new AssertionError("not a whole-collection read: " + method);
        };
        return (Flexemarkets) Proxy.newProxyInstance(Flexemarkets.class.getClassLoader(),
                new Class<?>[] {Flexemarkets.class}, handler);
    }

    private static <T> List<T> _rows(JsonNode cases, String call, Class<T> type) {
        for (JsonNode c : cases) {
            if (call.equals(c.path("call").asString())) {
                JsonNode row = c.path("requests").get(0).path("response").path("body").get(0);
                return LongStream.of(1, 2, 3)
                        .mapToObj(id -> {
                            ObjectNode copy = ((ObjectNode) row).deepCopy();
                            copy.put("id", id);
                            return HttpFlexemarkets.MAPPER.treeToValue(copy, type);
                        })
                        .toList();
            }
        }
        throw new AssertionError("no route case for " + call + " to take a row from");
    }
}
