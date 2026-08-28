package fm.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;

import fm.Market;
import fm.Order;
import fm.OrderBook;
import fm.Side;
import fm.Trade;
import fm.Trades;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every behaviour fixture, driven through this SDK's aggregators.
 *
 * <p>{@link WireFixturesTest} next door compares <em>parsed field values</em>:
 * one payload in, one set of fields out. It says nothing about what
 * {@link OrderBook} and {@link Trades} do with a sequence of them, which is
 * where the three SDKs have actually been wrong together — a book that
 * double-counts a cancel and a tape that holds its trades backwards both parse
 * every field correctly.
 *
 * <p>So these are inputs and answers rather than payloads and fields: a market,
 * a list of update steps, and what the aggregator must hold at the end. Java,
 * Python and TypeScript each run all of them, so a behaviour cannot be right in
 * one SDK and wrong in another without saying so.
 *
 * <p>See {@code sdks/fixtures/README.md}.
 */
class BehaviourFixturesTest {

    private static final Path FIXTURES =
            Path.of("..", "..", "fixtures", "behaviour").toAbsolutePath().normalize();

    private static final List<String> AGGREGATORS = List.of("OrderBook", "Trades");

    private record Fixture(String name, JsonNode document) {
        @Override
        public String toString() { return name; }
    }

    static Stream<Fixture> fixtures() throws Exception {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            List<Fixture> found = new ArrayList<>();
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                found.add(new Fixture(
                        path.getFileName().toString().replace(".json", ""),
                        HttpFlexemarkets.MAPPER.readTree(Files.readString(path))));
            }
            return found.stream();
        }
    }

    private static Market market(JsonNode doc) {
        return HttpFlexemarkets.MAPPER.treeToValue(doc.get("market"), Market.class);
    }

    private static Order[] orders(JsonNode step) {
        List<Order> parsed = new ArrayList<>();
        for (JsonNode node : step.get("orders")) {
            parsed.add(HttpFlexemarkets.MAPPER.treeToValue(node, Order.class));
        }
        return parsed.toArray(new Order[0]);
    }

    private static List<List<Long>> levels(Map<Long, Long> book) {
        List<List<Long>> flattened = new ArrayList<>();
        book.forEach((price, units) -> flattened.add(List.of(price, units)));
        return flattened;
    }

    private static List<List<Long>> expectedLevels(JsonNode node) {
        List<List<Long>> wanted = new ArrayList<>();
        for (JsonNode level : node) {
            wanted.add(List.of(level.get(0).asLong(), level.get(1).asLong()));
        }
        return wanted;
    }

    @ParameterizedTest(name = "behaviour: {0}")
    @MethodSource("fixtures")
    void behaviour(Fixture fixture) {
        JsonNode doc = fixture.document();

        List<Long> delivered = new ArrayList<>();
        for (JsonNode step : doc.get("steps")) {
            for (JsonNode order : step.get("orders")) {
                delivered.add(order.get("id").asLong());
            }
        }
        List<Long> declared = new ArrayList<>();
        doc.get("deliveredIds").forEach(id -> declared.add(id.asLong()));
        assertEquals(declared, delivered,
                "the fixture's input is not in the order it declares. deliveredIds is "
                + "what stops a case being quietly reordered into one that proves "
                + "nothing — the trades-ordering fixture is only a test at all "
                + "because its input arrives newest first.");

        switch (doc.get("type").asString()) {
            case "OrderBook" -> checkOrderBook(doc);
            case "Trades" -> checkTrades(doc);
            default -> fail("no aggregator for type " + doc.get("type").asString());
        }
    }

    private void checkOrderBook(JsonNode doc) {
        OrderBook book = new OrderBook(market(doc));
        for (JsonNode step : doc.get("steps")) {
            if (step.path("clear").asBoolean(false)) {
                book.clear();
            }
            book.update(orders(step));
        }


        JsonNode expect = doc.get("expect");
        expect.propertyNames().forEach(key -> {
            JsonNode want = expect.get(key);
            switch (key) {
                case "bestBuyPrice" -> assertEquals(want.asLong(), book.bestBuyPrice(), key);
                case "bestBuyUnits" -> assertEquals(want.asLong(), book.bestBuyUnits(), key);
                case "bestSellPrice" -> assertEquals(want.asLong(), book.bestSellPrice(), key);
                case "bestSellUnits" -> assertEquals(want.asLong(), book.bestSellUnits(), key);
                case "hasValueBuy" -> assertEquals(want.asBoolean(), book.hasValue(Side.BUY), key);
                case "hasValueSell" -> assertEquals(want.asBoolean(), book.hasValue(Side.SELL), key);
                case "buyLevels" -> assertEquals(expectedLevels(want), levels(book.buyLevels()), key);
                case "sellLevels" -> assertEquals(expectedLevels(want), levels(book.sellLevels()), key);
                default -> fail("fixture asks for unknown key " + key);
            }
        });
    }

    private void checkTrades(JsonNode doc) {
        Trades tape = new Trades(market(doc));
        int index = 0;
        for (JsonNode step : doc.get("steps")) {
            if (step.path("clear").asBoolean(false)) {
                tape.clear();
            }

            Trade[] added = tape.update(orders(step));

            // What update() reports it added is what MarketView dispatches
            // onTrade from. A step that declares `adds` pins it -- including
            // the zero, which is the update a handler must stay silent through.
            if (step.has("adds")) {
                assertEquals(step.get("adds").asInt(), added.length,
                        "step " + index + " (" + step.path("note").asString("") + ") "
                        + "reported " + added.length + " new trades");
            }
            index++;
        }

        JsonNode expect = doc.get("expect");
        Trade[] held = tape.mostRecentTrades();

        if (expect.has("size")) {
            assertEquals(expect.get("size").asInt(), tape.size(), "size");
        }

        if (expect.has("trades")) {
            JsonNode wanted = expect.get("trades");
            assertEquals(wanted.size(), held.length,
                    "tape holds " + held.length + " trades, expected " + wanted.size());
            for (int i = 0; i < held.length; i++) {
                checkTrade(held[i], wanted.get(i), "trades[" + i + "]");
            }
        }

        if (expect.has("last")) {
            if (expect.get("last").isNull()) {
                assertNull(tape.last());
            } else {
                assertNotNull(tape.last(), "last() is null but the tape is not empty");
                checkTrade(tape.last(), expect.get("last"), "last()");
            }
        }

        // Last, because it empties the tape.
        if (expect.has("drain")) {
            assertEquals(expect.get("drain").get("count").asInt(), tape.drain().length, "drain");
            assertEquals(expect.get("drain").get("sizeAfter").asInt(), tape.size(), "size after drain");
            assertNull(tape.last());
            assertEquals(0, tape.drain().length);
        }
    }

    private void checkTrade(Trade trade, JsonNode expected, String where) {
        expected.propertyNames().forEach(key -> {
            JsonNode want = expected.get(key);
            switch (key) {
                case "price" -> assertEquals(want.asLong(), trade.price(), where + ".price");
                case "units" -> assertEquals(want.asLong(), trade.units(), where + ".units");
                case "restingId" -> assertEquals(want.asLong(), trade.resting().id(), where + ".restingId");
                case "aggressorId" -> assertEquals(want.asLong(), trade.aggressor().id(), where + ".aggressorId");
                case "restingOwnerId" ->
                        assertEquals(want.asLong(), trade.resting().ownerId(), where + ".restingOwnerId");
                case "aggressorOwnerId" ->
                        assertEquals(want.asLong(), trade.aggressor().ownerId(), where + ".aggressorOwnerId");
                case "at" -> {
                    assertNotNull(trade.at(), where + ".at: expected an instant, got null");
                    assertEquals(want.get("epochMilli").asLong(), trade.at().toEpochMilli(),
                            where + ".at is the wrong side's stamp — the trade happened when "
                            + "the aggressor arrived, not when the quote it took was posted");
                }
                default -> fail(where + ": fixture asks for unknown key " + key);
            }
        });
    }

    @Test
    void thereAreFixturesToRun() throws Exception {
        // Guard the guard: a bad path would report everything passing.
        assertTrue(fixtures().count() >= 4, "too few behaviour fixtures found in " + FIXTURES);
    }

    @Test
    void everyFixtureTypeHasAnAggregator() throws Exception {
        List<String> unmapped = fixtures()
                .map(f -> f.document().get("type").asString())
                .distinct()
                .filter(type -> !AGGREGATORS.contains(type))
                .toList();
        assertTrue(unmapped.isEmpty(), "fixtures exist for " + unmapped + ", which nothing here drives");
    }
}
