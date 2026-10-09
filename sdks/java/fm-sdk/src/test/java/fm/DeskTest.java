package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fm.event.GapEvent;
import fm.event.OrdersUpdate;
import fm.internal.DefaultDesk;
import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.OrderType;

/**
 * The desk, driven by a scripted stream rather than a live server.
 *
 * <p>This is the coverage {@link Desk} did not have. The four tests that name
 * one are {@code @EnabledIf("liveServerReady")} and skip on every run, so
 * seeding, sequence filtering, gap detection and reseeding shipped untested.
 *
 * <p>These are deliberately about the book's <em>contents</em>. fm-robots'
 * VentureSequenceGapTest covers the same resync and asserts on stderr -- that
 * the gap was named -- which stays green even if the book it rebuilt is wrong.
 *
 * <p>A desk dispatches on its own virtual thread, so every assertion here is
 * made from a different thread than the one applying the update. {@link #_await}
 * is what makes that legible: it polls rather than sleeping, so a slow machine
 * waits longer instead of failing.
 */
class DeskTest {

    private static final long MP = 7L;

    private static Market _market(long id, String symbol) {
        return new Market(id, MP, symbol, symbol, symbol, false, 0, 10_000, 1, 1, 100, 1);
    }

    private static Order _limit(Market market, long id, OrderSide side, long units, long price) {
        return new Order(null, null, id, id, id, null,
                         OrderType.LIMIT, side, units, price, null, null,
                         MP, 1L, market.symbol(), market.id(), null, null);
    }

    /** Polls for a condition instead of sleeping on it; the desk applies on its own thread. */
    private static void _await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(2);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    @Timeout(20)
    void aDeskSeedsItsBooksFromTheSnapshot() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha),
            new Snapshot<>(List.of(_limit(alpha, 101L, OrderSide.BUY, 5, 1000)), 4L),
            new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            assertThat(desk.book(alpha.id()).bestBuyPrice()).isEqualTo(1000L);
            assertThat(desk.book(alpha.id()).bestBuyUnits()).isEqualTo(5L);
        }
    }

    @Test
    @Timeout(20)
    void aDeltaAfterTheSeedIsApplied() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha),
            new Snapshot<>(List.of(_limit(alpha, 101L, OrderSide.BUY, 5, 1000)), 4L),
            new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 102L, OrderSide.BUY, 3, 1100) }, 5L));

            _await("the better bid to land",
                   () -> desk.book(alpha.id()).bestBuyPrice() == 1100L);
            assertThat(desk.book(alpha.id()).bestBuyUnits()).isEqualTo(3L);
        }
    }

    /**
     * The seed carries the sequence it was correct as of, and a delta at or
     * below it is already in the book. A book aggregates by price level rather
     * than by order id, so applying one twice adds its units twice and the
     * book reads deeper than the market is -- silently.
     */
    @Test
    @Timeout(20)
    void aDeltaAlreadyInTheSeedIsNotAppliedTwice() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        Order resting = _limit(alpha, 101L, OrderSide.BUY, 5, 1000);
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(resting), 4L), new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            // seq 4 == asOfSeq: the snapshot already reflects it.
            fake.post(new OrdersUpdate(new Order[] { resting }, 4L));
            // A later delta we can wait on, so the re-delivery has certainly been seen.
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 102L, OrderSide.SELL, 2, 2000) }, 5L));

            _await("the marker delta to land",
                   () -> desk.book(alpha.id()).bestSellPrice() == 2000L);

            assertThat(desk.book(alpha.id()).bestBuyUnits())
                .as("re-delivered seed order counted twice")
                .isEqualTo(5L);
        }
    }

    /** Both legs of one trade: {@code restingId} rested, {@code aggressorId} took it. */
    private static Order[] _trade(Market market, long restingId, long aggressorId, long price) {
        Instant rested = Instant.parse("2026-10-08T10:00:00Z").plusSeconds(restingId);
        Instant taken = rested.plusSeconds(1000);
        return new Order[] {
            new Order(rested, taken, restingId, restingId, restingId, aggressorId, OrderType.LIMIT, OrderSide.SELL,
                      1, price, null, 900L, MP, 1L, market.symbol(), market.id(), null, null),
            new Order(taken, taken, aggressorId, aggressorId, aggressorId, restingId, OrderType.LIMIT, OrderSide.BUY,
                      1, price, null, 901L, MP, 1L, market.symbol(), market.id(), null, null)
        };
    }

    /**
     * Each tape is seeded from its own market's read. Read for every market at
     * once, a busy market's legs filled the shared limit and a quiet market's
     * tape came up empty though it had traded (fm-server#1029).
     */
    @Test
    @Timeout(20)
    void eachTapeIsSeededFromItsOwnMarketsRead() throws Exception {
        Market busy = _market(1L, "BUSY");
        Market quiet = _market(2L, "QUIET");
        var legs = new ArrayList<Order>(List.of(_trade(busy, 101L, 102L, 500)));
        legs.addAll(List.of(_trade(quiet, 11L, 12L, 700)));
        var fake = new FakeFlexemarkets(
            List.of(busy, quiet), new Snapshot<>(List.of(), 4L), new Snapshot<>(legs, 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(busy, quiet))) {
            assertThat(fake.marketTradeReads()).containsExactly(
                busy.id() + "/" + 2 * desk.tape(busy.id()).capacity(),
                quiet.id() + "/" + 2 * desk.tape(quiet.id()).capacity());
            assertThat(fake.marketplaceTradeReads()).as("no read for every market at once").isZero();
            assertThat(desk.tape(quiet.id()).mostRecentPrices()).containsExactly(700L);
        }
    }

    /**
     * The trades snapshot is read after the orders snapshot whose sequence the
     * desk follows, so a trade made in between is in the seed and again in a
     * delta past the watermark. The tape keeps it once, and onTrade does not
     * announce it a second time.
     */
    @Test
    @Timeout(20)
    void aTradeInTheSeedAndAgainInADeltaIsKeptOnce() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        Order[] trade = _trade(alpha, 101L, 102L, 500);
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 4L), new Snapshot<>(List.of(trade), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            var announced = new java.util.concurrent.atomic.AtomicInteger();
            desk.onTrade(alpha.id(), t -> announced.incrementAndGet());

            fake.post(new OrdersUpdate(trade, 5L));
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 200L, OrderSide.SELL, 2, 2000) }, 6L));
            _await("the marker delta to land", () -> desk.book(alpha.id()).bestSellPrice() == 2000L);

            assertThat(desk.tape(alpha.id()).size()).isEqualTo(1);
            assertThat(announced.get()).isZero();
        }
    }

    @Test
    @Timeout(20)
    void booksAndTapesCoverEveryMarket() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        Market beta  = _market(2L, "BETA");
        var fake = new FakeFlexemarkets(
            List.of(alpha, beta), new Snapshot<>(List.of(), 1L), new Snapshot<>(List.of(), 1L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha, beta))) {
            assertThat(desk.books()).hasSize(2);
            assertThat(desk.tapes()).hasSize(2);
            assertThat(desk.books().stream().map(Book::marketId))
                .containsExactlyInAnyOrder(alpha.id(), beta.id());
        }
    }

    /**
     * A gap must leave the book <em>right</em>, not merely leave a message on
     * stderr. The reseed answers a different book from the first read, so a
     * resync that quietly kept the stale one fails here.
     */
    @Test
    @Timeout(20)
    void aGapReseedsTheBookFromTheSnapshotAndSaysSo() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha),
            new Snapshot<>(List.of(_limit(alpha, 101L, OrderSide.BUY, 5, 1000)), 4L),
            new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            var gaps = new AtomicInteger();
            desk.onGap(g -> gaps.incrementAndGet());

            // What the resync will read: a different book entirely.
            fake.nextActiveOrders(
                new Snapshot<>(List.of(_limit(alpha, 201L, OrderSide.BUY, 9, 1500)), 40L));

            // seq 41 with lastApplied 4 is a gap of 36 frames.
            fake.post(new OrdersUpdate(new Order[0], 41L));

            _await("the reseeded book", () -> desk.book(alpha.id()).bestBuyPrice() == 1500L);

            assertThat(desk.book(alpha.id()).bestBuyUnits()).isEqualTo(9L);
            assertThat(gaps.get()).as("onGap fired").isEqualTo(1);
            assertThat(fake.activeReads()).as("one seed at open, one at the gap").isEqualTo(2);
        }
    }

    /**
     * A reconnect is the largest possible gap: whatever happened while the
     * socket was down is missing, so the desk reseeds from the snapshot and
     * tells its recovery handlers it did. No test reached this before
     * 2026-10-06 (test risk map, plan item 5).
     */
    @Test
    @Timeout(20)
    void aReconnectReseedsTheBookAndSaysSo() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha),
            new Snapshot<>(List.of(_limit(alpha, 101L, OrderSide.BUY, 5, 1000)), 4L),
            new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            var recoveries = new java.util.concurrent.CopyOnWriteArrayList<fm.event.DeskRecovery>();
            desk.onRecovery(recoveries::add);
            fake.nextActiveOrders(new Snapshot<>(List.of(_limit(alpha, 201L, OrderSide.BUY, 9, 1500)), 40L));

            fake.post(new fm.event.StreamReconnected(MP));

            _await("the reseeded book", () -> desk.book(alpha.id()).bestBuyPrice() == 1500L);
            _await("the recovery handler", () -> !recoveries.isEmpty());
            assertThat(recoveries).containsExactly(new fm.event.DeskRecovery(MP, true, null));
        }
    }

    /**
     * A reseed that fails leaves the stream live and the book stale. Handlers
     * hear that it failed and why, and the desk keeps applying what arrives
     * rather than dying with the snapshot read.
     */
    @Test
    @Timeout(20)
    void aReseedThatFailsSaysTheDeskIsStaleAndKeepsGoing() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha),
            new Snapshot<>(List.of(_limit(alpha, 101L, OrderSide.BUY, 5, 1000)), 4L),
            new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            var recoveries = new java.util.concurrent.CopyOnWriteArrayList<fm.event.DeskRecovery>();
            desk.onRecovery(recoveries::add);
            fake.failActiveOrders(new IllegalStateException("503 from the server"));

            fake.post(new fm.event.StreamReconnected(MP));
            _await("the recovery handler", () -> !recoveries.isEmpty());

            assertThat(recoveries.get(0).success()).isFalse();
            assertThat(recoveries.get(0).reason()).contains("503");

            fake.failActiveOrders(null);
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 102L, OrderSide.BUY, 3, 1100) }, 5L));
            _await("a delta after the failed reseed", () -> desk.book(alpha.id()).bestBuyPrice() == 1100L);
        }
    }

    /**
     * A caller's handler is the caller's code, run on the desk's dispatcher.
     * One that throws must not end it: gap and recovery handlers were already
     * guarded, but a book, trade, session or holding handler that threw
     * escaped _drain, the dispatcher thread died, and the desk stopped
     * applying updates with nothing to say so.
     */
    @Test
    @Timeout(20)
    void aHandlerThatThrowsDoesNotStopTheDesk() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 1L), new Snapshot<>(List.of(), 1L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            desk.onBookChange(alpha.id(), book -> { throw new IllegalStateException("a bug in the caller's handler"); });

            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 101L, OrderSide.BUY, 5, 1000) }, 2L));
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 102L, OrderSide.BUY, 3, 1100) }, 3L));

            _await("the update after the throwing handler",
                   () -> desk.book(alpha.id()).bestBuyPrice() == 1100L);
        }
    }

    @Test
    @Timeout(20)
    void consecutiveFramesAreNotAGap() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 4L), new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            var gaps = new AtomicInteger();
            desk.onGap((GapEvent g) -> gaps.incrementAndGet());

            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 102L, OrderSide.BUY, 1, 900) }, 5L));
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 103L, OrderSide.BUY, 1, 950) }, 6L));

            _await("both deltas to land", () -> desk.book(alpha.id()).bestBuyPrice() == 950L);

            assertThat(gaps.get()).isZero();
            assertThat(fake.activeReads()).as("no reseed").isEqualTo(1);
        }
    }

    /**
     * "Reads are atomic; a caller never sees a half-applied delta" is what
     * {@link Desk#book} promises, and nothing checked it. One update carrying
     * many orders must land all-or-nothing: a reader on another thread sees
     * the level empty or sees it whole, never partway through.
     *
     * <p>This matters more than it looks. Book.update is synchronized over the
     * whole array, so the promise holds today by construction -- but the
     * obvious "optimisation" of locking per order would keep every other test
     * in this file green while breaking exactly this.
     *
     * <p>Honest about its limits: a race that is not hit is not proven absent,
     * so this can only fail when it actually observes tearing. Five hundred
     * orders in one frame is what widens the window enough to make that
     * likely. Verified by removing synchronized from Book.update, which fails
     * it.
     */
    @Test
    @Timeout(30)
    void oneUpdateLandsAllOrNothing() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 4L), new Snapshot<>(List.of(), 4L));

        final int orders = 500;
        Order[] batch = new Order[orders];
        for (int i = 0; i < orders; i++) {
            batch[i] = _limit(alpha, 200L + i, OrderSide.BUY, 1, 1000);
        }

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            var seen = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());
            var stop = new java.util.concurrent.atomic.AtomicBoolean();

            Thread reader = Thread.startVirtualThread(() -> {
                while (!stop.get()) {
                    seen.add(desk.book(alpha.id()).bestBuyUnits());
                }
            });

            fake.post(new OrdersUpdate(batch, 5L));
            _await("the batch to land", () -> desk.book(alpha.id()).bestBuyUnits() == orders);
            stop.set(true);
            reader.join();

            assertThat(seen)
                .as("a reader saw the level part-built, so the update was not atomic")
                .isSubsetOf(-1L, (long) orders);
        }
    }


    /**
     * {@link Desk#over} is how a host with its own {@link Flexemarkets} gets a
     * desk, and every test above built {@link DefaultDesk} directly, so the
     * factory itself never ran.
     */
    @Test
    @Timeout(20)
    void aDeskOverAnyConnectionIsSeededAndLive() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha),
            new Snapshot<>(List.of(_limit(alpha, 101L, OrderSide.BUY, 5, 1000)), 4L),
            new Snapshot<>(List.of(), 4L));

        try (var desk = Desk.over(fake, MP, List.of(alpha))) {
            assertThat(desk.marketplaceId()).isEqualTo(MP);
            assertThat(desk.book(alpha.id()).bestBuyPrice()).isEqualTo(1000L);

            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 102L, OrderSide.BUY, 3, 1100) }, 5L));
            _await("the delta to land", () -> desk.book(alpha.id()).bestBuyPrice() == 1100L);
        }
    }

    /** The session and holding a desk holds are the last ones streamed, and handlers hear each until they unsubscribe. */
    @Test
    @Timeout(20)
    void sessionAndHoldingUpdatesAreKeptAndAnnounced() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 1L), new Snapshot<>(List.of(), 1L));
        var open = new fm.model.Session(MP, 3L, 30L, 30L, fm.model.Session.STATE_OPEN, "s", null, null, null);
        var paused = new fm.model.Session(MP, 3L, 30L, 30L, fm.model.Session.STATE_PAUSED, "s", null, null, null);
        var rich = new fm.model.Holding(MP, 30L, 3L, 1L, "h", 5_000, 4_000, List.of());
        var poor = new fm.model.Holding(MP, 30L, 3L, 1L, "h", 10, 10, List.of());

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            assertThat(desk.session()).as("nothing streamed yet").isNull();
            assertThat(desk.holding()).as("nothing streamed yet").isNull();
            var sessions = new java.util.concurrent.CopyOnWriteArrayList<fm.model.Session>();
            var holdings = new java.util.concurrent.CopyOnWriteArrayList<fm.model.Holding>();
            Subscription onSession = desk.onSessionChange(sessions::add);
            Subscription onHolding = desk.onHoldingChange(holdings::add);

            fake.post(open);
            fake.post(rich);
            _await("the holding", () -> rich.equals(desk.holding()));
            assertThat(desk.session()).isEqualTo(open);
            assertThat(sessions).containsExactly(open);
            assertThat(holdings).containsExactly(rich);

            onSession.close();
            onHolding.close();
            fake.post(paused);
            fake.post(poor);
            _await("the second holding", () -> poor.equals(desk.holding()));
            assertThat(desk.session()).isEqualTo(paused);
            assertThat(sessions).as("unsubscribed").containsExactly(open);
            assertThat(holdings).as("unsubscribed").containsExactly(rich);
        }
    }

    /**
     * A trade reaches the handlers for its own market, and only those. The
     * other trade test asserts a trade is <em>not</em> announced twice, which
     * a desk that never announced one at all would pass.
     */
    @Test
    @Timeout(20)
    void aTradeIsAnnouncedToItsOwnMarketsHandlers() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        Market beta = _market(2L, "BETA");
        var fake = new FakeFlexemarkets(
            List.of(alpha, beta), new Snapshot<>(List.of(), 4L), new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha, beta))) {
            var onAlpha = new java.util.concurrent.CopyOnWriteArrayList<fm.model.Trade>();
            var onBeta = new java.util.concurrent.CopyOnWriteArrayList<fm.model.Trade>();
            Subscription alphaTrades = desk.onTrade(alpha.id(), onAlpha::add);
            desk.onTrade(beta.id(), onBeta::add);

            fake.post(new OrdersUpdate(_trade(alpha, 101L, 102L, 500), 5L));
            _await("the trade to be announced", () -> !onAlpha.isEmpty());
            assertThat(onAlpha).extracting(fm.model.Trade::price).containsExactly(500L);
            assertThat(onAlpha.get(0).aggressor().id()).isEqualTo(102L);

            alphaTrades.close();
            fake.post(new OrdersUpdate(_trade(alpha, 103L, 104L, 510), 6L));
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 200L, OrderSide.SELL, 2, 2000) }, 7L));
            _await("the marker delta to land", () -> desk.book(alpha.id()).bestSellPrice() == 2000L);

            assertThat(desk.tape(alpha.id()).mostRecentPrices()).containsExactly(500L, 510L);
            assertThat(onAlpha).as("unsubscribed before the second trade").hasSize(1);
            assertThat(onBeta).as("another market's trade").isEmpty();
        }
    }

    @Test
    @Timeout(20)
    void ordersAreSentThroughTheConnectionForThisMarketplace() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 1L), new Snapshot<>(List.of(), 1L));

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            assertThat(desk.submitLimit(alpha.id(), OrderSide.SELL, 2, 1500).price()).isEqualTo(1500L);
            assertThat(desk.submitCancel(alpha.id(), 900L).supplier()).isEqualTo(900L);

            assertThat(fake.submitted()).containsExactly(
                MP + "/" + alpha.id() + " SELL 2@1500",
                MP + "/" + alpha.id() + " CANCEL 900");
        }
    }

    @Test
    @Timeout(20)
    void aClosedDeskRefusesToBeReadAndClosesOnce() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 1L), new Snapshot<>(List.of(), 1L));
        var desk = new DefaultDesk(fake, MP, List.of(alpha));

        desk.close();
        desk.close();

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
            .isThrownBy(() -> desk.book(alpha.id()))
            .withMessage("Desk for marketplace " + MP + " is closed");
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
            .isThrownBy(() -> desk.submitLimit(alpha.id(), OrderSide.BUY, 1, 1000));
        assertThat(fake.submitted()).as("nothing reached the connection").isEmpty();
    }

    /**
     * Which markets an update touched is gathered into an array sized for
     * sixteen and grown past it. One update across seventeen markets is what
     * makes it grow, and every book handler must still hear its own.
     */
    @Test
    @Timeout(20)
    void anUpdateAcrossMoreThanSixteenMarketsReachesEveryBookHandler() throws Exception {
        var markets = new ArrayList<Market>();
        for (long id = 1; id <= 17; id++) markets.add(_market(id, "M" + id));
        var fake = new FakeFlexemarkets(
            markets, new Snapshot<>(List.of(), 4L), new Snapshot<>(List.of(), 4L));

        try (var desk = new DefaultDesk(fake, MP, markets)) {
            var heard = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
            for (var market : markets) desk.onBookChange(market.id(), book -> heard.add(book.marketId()));

            Order[] update = markets.stream()
                .map(m -> _limit(m, 100L + m.id(), OrderSide.BUY, 1, 1000))
                .toArray(Order[]::new);
            fake.post(new OrdersUpdate(update, 5L));

            _await("all seventeen handlers", () -> heard.size() == 17);
            assertThat(heard).containsExactlyInAnyOrderElementsOf(markets.stream().map(Market::id).toList());
        }
    }

    /**
     * A dropped or unreadable stream is said in the log, in words, and the
     * desk keeps applying what arrives after it. A failure with no message --
     * a reset connection carries none -- is named by its type, and a drop with
     * no cause at all says that, rather than either printing "null".
     */
    @Test
    @Timeout(20)
    void aDroppedOrUnreadableStreamIsLoggedAndTheDeskCarriesOn() throws Exception {
        Market alpha = _market(1L, "ALPHA");
        var fake = new FakeFlexemarkets(
            List.of(alpha), new Snapshot<>(List.of(), 4L), new Snapshot<>(List.of(), 4L));
        var logger = java.util.logging.Logger.getLogger(DefaultDesk.class.getName());
        var logged = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var formatter = new java.util.logging.SimpleFormatter();
        var capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { logged.add(formatter.formatMessage(r)); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(capture);

        try (var desk = new DefaultDesk(fake, MP, List.of(alpha))) {
            fake.post(new fm.event.StreamDropped(null));
            fake.post(new fm.event.StreamDropped(new java.io.IOException()));
            fake.post(new fm.event.StreamDropped(new java.io.IOException("connection reset")));
            fake.post(new fm.event.FrameUnreadable("STOMP ERROR: no such marketplace", null));
            fake.post(new OrdersUpdate(new Order[] { _limit(alpha, 102L, OrderSide.BUY, 3, 1100) }, 5L));

            _await("the delta after them", () -> desk.book(alpha.id()).bestBuyPrice() == 1100L);
            assertThat(logged).containsExactly(
                "WS transport error on marketplace 7: no cause reported",
                "WS transport error on marketplace 7: IOException",
                "WS transport error on marketplace 7: IOException: connection reset",
                "WS error on marketplace 7: STOMP ERROR: no such marketplace");
        } finally {
            logger.removeHandler(capture);
        }
    }

}
