package fm.internal;

import fm.model.Account;
import fm.model.ClientConnection;
import fm.model.Holding;
import fm.model.Market;
import fm.model.Marketplace;
import fm.model.Order;
import fm.model.Person;
import fm.model.Security;
import fm.model.Session;
import fm.model.Token;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every shared fixture, read with the mapper the SDK actually uses.
 *
 * <p>See {@code sdks/fixtures/README.md}. The short version: check-parity.py
 * compares the three SDKs' declarations and can only see field names, which is
 * how {@code approval} stayed wrong in Python and TypeScript while the check
 * reported ok. These compare values.
 *
 * <p>Java has one parser per type — Jackson, through {@link
 * HttpFlexemarkets#MAPPER}, which {@link Events} is handed too — so unlike the
 * other two SDKs there is no REST/WebSocket pair to hold together here. What
 * this does catch is the annotations: every record binds the wire through a
 * {@code @JsonCreator} taking {@code String} timestamps, and every type carries
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}. That combination fails
 * quietly — a renamed component binds to nothing and arrives as null rather
 * than throwing.
 */
class WireFixturesTest {

    private static final Path FIXTURES =
            Path.of("..", "..", "fixtures").toAbsolutePath().normalize();

    /** Wire type name -> the record the SDK deserializes it into. */
    private static final Map<String, Class<?>> TYPES = Map.of(
            "Order", fm.model.Order.class,
            "Session", fm.model.Session.class,
            "Holding", fm.model.Holding.class,
            "Account", fm.model.Account.class,
            "Person", fm.model.Person.class,
            "Market", fm.model.Market.class,
            "Marketplace", fm.model.Marketplace.class,
            "ClientConnection", fm.model.ClientConnection.class,
            "Security", fm.model.Security.class,
            "Token", fm.model.Token.class);

    /**
     * The snapshot envelope is not a record the mapper binds -- it is a shape
     * the SDK has to recognise, which is exactly why it has broken twice. The
     * fixtures for it run through the real unwrap.
     */
    record OrdersSnapshot(java.util.List<fm.model.Order> orders) { }

    /** Types the fixture run handles by a route other than {@link #TYPES}. */
    private static final java.util.Set<String> HANDLED_WITHOUT_A_TYPE =
            java.util.Set.of("OrdersSnapshot");

    record Fixture(String name, String type, JsonNode payload, JsonNode expect) {
        @Override
        public String toString() {
            return name + " -> " + type;
        }
    }

    static Stream<Fixture> fixtures() throws IOException {
        try (var files = Files.list(FIXTURES)) {
            var out = new ArrayList<Fixture>();
            for (var path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var doc = HttpFlexemarkets.MAPPER.readTree(Files.readString(path));
                var name = path.getFileName().toString().replace(".json", "");
                out.add(new Fixture(name, doc.get("type").asString(),
                                    doc.get("payload"), doc.get("expect")));
            }
            return out.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixture(Fixture fixture) {
        Object parsed;
        if ("OrdersSnapshot".equals(fixture.type())) {
            parsed = new OrdersSnapshot(HttpFlexemarkets._unwrapOrders(
                    new fm.Snapshot<>(fixture.payload(), fm.Snapshot.NO_SEQ)).body());
        } else {
            var type = TYPES.get(fixture.type());
            assertNotNull(type, "no Java type mapped for " + fixture.type());
            parsed = HttpFlexemarkets.MAPPER.treeToValue(fixture.payload(), type);
        }
        assertNotNull(parsed, fixture.name() + ": deserialized to null");

        fixture.expect().propertyStream().forEach(entry ->
                _check(_read(parsed, entry.getKey()), entry.getValue(),
                      fixture.name() + "." + entry.getKey()));
    }

    /** The value of one record component, by its wire name. */
    private static Object _read(Object parsed, String name) {
        Method accessor;
        try {
            accessor = parsed.getClass().getMethod(name);
        } catch (NoSuchMethodException e) {
            return fail(parsed.getClass().getSimpleName() + " has no component '" + name + "'");
        }
        try {
            return accessor.invoke(parsed);
        } catch (ReflectiveOperationException e) {
            return fail("could not read " + name, e);
        }
    }

    private static void _check(Object actual, JsonNode expected, String where) {
        if (expected.isObject() && expected.has("epochMilli") && expected.size() == 1) {
            assertNotNull(actual, where + ": expected an instant, got null");
            var instant = assertInstanceOf(Instant.class, actual,
                    where + ": expected an Instant, got " + actual.getClass().getSimpleName()
                            + " " + actual + " -- the wire value was left unconverted");
            assertEquals(expected.get("epochMilli").asLong(), instant.toEpochMilli(),
                    where + ": " + instant + " is not "
                            + Instant.ofEpochMilli(expected.get("epochMilli").asLong()));
            return;
        }

        if (expected.isArray()) {
            assertNotNull(actual, where + ": expected a list, got null");
            // Java spells some of these as arrays (Person.roles) and some as
            // List. The fixture does not care which, only what is in it.
            var list = actual instanceof Object[] array
                    ? List.of(array)
                    : assertInstanceOf(List.class, actual, where + ": expected a List or array");
            assertEquals(expected.size(), list.size(), where + ": wrong length");
            for (int i = 0; i < expected.size(); i++) {
                var want = expected.get(i);
                if (want.isObject()) {
                    int index = i;
                    want.propertyStream().forEach(e ->
                            _check(_read(list.get(index), e.getKey()), e.getValue(),
                                  where + "[" + index + "]." + e.getKey()));
                } else {
                    _check(list.get(i), want, where + "[" + i + "]");
                }
            }
            return;
        }

        if (expected.isObject()) {
            expected.propertyStream().forEach(e ->
                    _check(_read(actual, e.getKey()), e.getValue(), where + "." + e.getKey()));
            return;
        }

        if (expected.isNull()) {
            // Null must survive as null. A primitive component cannot hold it,
            // which is the point: `approval` had to be boxed for exactly this.
            assertEquals(null, actual, where + ": expected null, got " + actual);
            return;
        }
        if (expected.isBoolean()) {
            assertEquals(expected.asBoolean(), actual, where);
            return;
        }
        if (expected.isNumber()) {
            assertNotNull(actual, where + ": expected " + expected.asLong() + ", got null");
            assertEquals(expected.asLong(), ((Number) actual).longValue(), where);
            return;
        }
        // Enums compare by name, so a parser that never converted is visible.
        assertEquals(expected.asString(), String.valueOf(actual), where);
    }

    @Test
    @DisplayName("there are fixtures to run")
    void thereAreFixturesToRun() throws IOException {
        // Guard the guard: a bad path would report everything passing.
        var found = fixtures().toList();
        assertTrue(found.size() >= 10, "only found " + found.size() + " fixtures in " + FIXTURES);
    }

    @Test
    @DisplayName("every fixture type has a Java type")
    void everyFixtureTypeIsMapped() throws IOException {
        // A fixture for a type nothing maps is a test that does not run.
        var unmapped = fixtures().map(Fixture::type).distinct()
                .filter(t -> !TYPES.containsKey(t) && !HANDLED_WITHOUT_A_TYPE.contains(t))
                .sorted().toList();
        assertEquals(List.of(), unmapped, "fixtures exist for types with no Java mapping");
    }
}
