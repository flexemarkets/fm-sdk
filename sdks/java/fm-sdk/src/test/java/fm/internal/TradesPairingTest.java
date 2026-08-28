package fm.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import fm.Market;
import fm.Order;
import fm.Trade;
import fm.Trades;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade tape: which side of a match it names, and what order it holds.
 *
 * <p>Both properties failed silently before {@link Trade} existed, which is why
 * they are asserted against a shared fixture rather than left to reading the
 * code:
 *
 * <ul>
 *   <li>the tape kept the <em>resting</em> order of each pair and dropped the
 *       incoming one, so a caller asking who took a trade got the maker — a
 *       real participant, at a real price, in an answer with nothing wrong on
 *       its face;</li>
 *   <li>it appended in the order the array arrived, and the
 *       {@code /v1/orders/recent-trades} snapshot that seeds it — on open, on a
 *       sequence gap, and after every reconnect — arrives newest <em>first</em>,
 *       so the tape was backwards and the last element was the oldest trade
 *       retained.</li>
 * </ul>
 *
 * <p>{@code sdks/fixtures/trades/pairing.json} is the same fixture the Python
 * and TypeScript suites read, so the three cannot drift on the answer.
 */
class TradesPairingTest {

    private static final Path FIXTURE = Path.of("..", "..", "fixtures", "trades", "pairing.json")
            .toAbsolutePath().normalize();

    private static JsonNode fixture() {
        try {
            return HttpFlexemarkets.MAPPER.readTree(Files.readString(FIXTURE));
        } catch (Exception e) {
            throw new AssertionError("cannot read " + FIXTURE, e);
        }
    }

    private static Trades tape() {
        var doc = fixture();
        var market = HttpFlexemarkets.MAPPER.treeToValue(doc.get("market"), Market.class);

        var orders = new ArrayList<Order>();
        for (var node : doc.get("orders")) {
            orders.add(HttpFlexemarkets.MAPPER.treeToValue(node, Order.class));
        }

        var trades = new Trades(market, 100);
        trades.update(orders.toArray(new Order[0]));
        return trades;
    }

    private static List<JsonNode> expected() {
        var list = new ArrayList<JsonNode>();
        fixture().get("expect").forEach(list::add);
        return list;
    }

    @Test
    @DisplayName("the fixture delivers the newer trade's pair first, as the snapshot does")
    void fixtureIsNewestFirst() {
        // Guard the guard: if the fixture already arrived oldest-first, the
        // ordering assertion below would pass on a tape that never sorted.
        // The claim is about the pairs, not the rows -- within a pair the
        // resting order deliberately carries the later stamp, so that reading
        // the time off the wrong side shows up in timeComesFromTheAggressor.
        var ids = new ArrayList<Long>();
        fixture().get("orders").forEach(node -> ids.add(node.get("id").asLong()));

        var positions = expected().stream()
                .map(e -> ids.indexOf(e.get("aggressorId").asLong()))
                .toList();

        for (int i = 1; i < positions.size(); i++) {
            assertTrue(positions.get(i) < positions.get(i - 1),
                    "the fixture must deliver the newer trade's pair first; "
                            + "aggressors sit at " + positions);
        }
    }

    @Test
    @DisplayName("the tape names the aggressor, not the resting order")
    void namesTheAggressor() {
        Trade[] actual = tape().mostRecentTrades();
        var expected = expected();

        assertEquals(expected.size(), actual.length);

        for (int i = 0; i < actual.length; i++) {
            var trade = actual[i];
            var want = expected.get(i);
            var where = "trade at " + want.get("price").asLong() + ": ";

            assertEquals(want.get("aggressorOwnerId").asLong(), trade.aggressor().ownerId(),
                    where + "named the wrong side as the aggressor; "
                            + want.get("restingOwnerId").asLong() + " is the resting side");
            assertEquals(want.get("restingOwnerId").asLong(), trade.resting().ownerId(), where);
            assertEquals(want.get("aggressorId").asLong(), trade.aggressor().id(), where);
            assertEquals(want.get("restingId").asLong(), trade.resting().id(), where);
        }
    }

    @Test
    @DisplayName("price and units come from the resting side")
    void priceAndUnitsFromResting() {
        Trade[] actual = tape().mostRecentTrades();
        var expected = expected();

        for (int i = 0; i < actual.length; i++) {
            assertEquals(expected.get(i).get("price").asLong(), actual[i].price());
            assertEquals(expected.get(i).get("units").asLong(), actual[i].units());
            assertEquals(actual[i].resting().price(), actual[i].price());
        }
    }

    @Test
    @DisplayName("the time comes from the aggressor, not the quote it took")
    void timeComesFromTheAggressor() {
        // Each resting order in the fixture carries a later stamp than its
        // aggressor, so reading the wrong side is visible here.
        Trade[] actual = tape().mostRecentTrades();
        var expected = expected();

        for (int i = 0; i < actual.length; i++) {
            assertNotNull(actual[i].at());
            assertEquals(expected.get(i).get("at").get("epochMilli").asLong(),
                    actual[i].at().toEpochMilli(),
                    "that is the resting order's stamp, not the aggressor's");
        }
    }

    @Test
    @DisplayName("the tape is oldest first even when the snapshot is not")
    void tapeIsOldestFirst() {
        var tape = tape();
        var expected = expected();

        long[] prices = tape.mostRecentPrices();
        assertEquals(expected.size(), prices.length);
        for (int i = 0; i < prices.length; i++) {
            assertEquals(expected.get(i).get("price").asLong(), prices[i]);
        }

        assertNotNull(tape.last());
        assertEquals(expected.get(expected.size() - 1).get("price").asLong(), tape.last().price(),
                "last() must be the newest trade; a tape that appends in array "
                        + "order returns the oldest one it retained");
    }

    @Test
    @DisplayName("an empty tape has no last trade")
    void emptyTapeHasNoLast() {
        var market = HttpFlexemarkets.MAPPER.treeToValue(fixture().get("market"), Market.class);
        assertNull(new Trades(market, 100).last());
    }

    @Test
    @DisplayName("drain takes the tape and leaves it empty")
    void drainEmptiesTheTape() {
        // Python and TypeScript have had drain() all along; Java had clear(),
        // which throws the trades away rather than handing them over, so a
        // consumer draining what accumulated since it last looked had to read
        // the tape and clear it in two steps with a race in between.
        var tape = tape();
        int held = tape.size();

        Trade[] drained = tape.drain();

        assertEquals(held, drained.length);
        assertEquals(expected().size(), drained.length);
        assertEquals(0, tape.size());
        assertNull(tape.last());
        assertEquals(0, tape.drain().length);
    }
}
