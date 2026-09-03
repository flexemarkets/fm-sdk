package fm;

import static org.assertj.core.api.Assertions.assertThat;

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

}
