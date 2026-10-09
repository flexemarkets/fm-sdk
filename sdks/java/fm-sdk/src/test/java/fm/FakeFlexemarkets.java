package fm;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import fm.error.ApiException;
import fm.model.*;
import fm.role.*;

/**
 * A {@link Flexemarkets} that answers from the test rather than a server.
 *
 * <p>Written because {@link Desk} had no executed test at all. The four that
 * name one are {@code @EnabledIf("liveServerReady")} and skip on every run, so
 * a public class that seeds from REST, filters deltas by sequence, detects
 * gaps and reseeds was shipping uncovered. The reason is visible here:
 * Flexemarkets is 53 abstract methods across six roles, so standing one up
 * costs 53 stubs before the first assertion. Paying that once, in one place,
 * is what makes the desk testable.
 *
 * <p>Everything a desk does not touch throws, so a test that wanders into an
 * unmodelled call is told rather than quietly handed a null. What a desk does
 * touch -- {@code markets}, {@code activeOrders}, {@code recentTrades} and
 * {@code subscribe} -- is scriptable, the two submits it passes on are
 * recorded, and {@link #post} hands the test the queue the desk is draining,
 * which is the only way to drive it: the desk dispatches on its own virtual
 * thread.
 */
class FakeFlexemarkets implements Flexemarkets {

    private final List<Market> _markets;
    private final AtomicReference<Snapshot<List<Order>>> _active;
    private final AtomicReference<Snapshot<List<Order>>> _recent;
    private final AtomicReference<BlockingQueue<Object>> _queue = new AtomicReference<>();
    private volatile int _activeReads = 0;
    private volatile RuntimeException _activeFailure;

    FakeFlexemarkets(List<Market> markets, Snapshot<List<Order>> active, Snapshot<List<Order>> recent) {
        this._markets = List.copyOf(markets);
        this._active = new AtomicReference<>(active);
        this._recent = new AtomicReference<>(recent);
    }

    /** Replace what the next seed reads, so a reseed can differ from the first. */
    void nextActiveOrders(Snapshot<List<Order>> next) {
        _active.set(next);
    }

    /** Make the next active-book reads fail with this, as a server that is down would; null to stop. */
    void failActiveOrders(RuntimeException failure) {
        _activeFailure = failure;
    }

    /** How many times a seed has read the active book -- one per open, one per gap. */
    int activeReads() {
        return _activeReads;
    }

    /** Hand an event to the desk's dispatcher, as the websocket would. */
    void post(Object event) throws InterruptedException {
        BlockingQueue<Object> q = _queue.get();
        if (null == q) throw new IllegalStateException("nothing has subscribed yet");
        q.put(event);
    }

    // --- what a desk uses ---------------------------------------------------

    @Override public List<Market> markets(long marketplaceId) { return _markets; }

    @Override public Snapshot<List<Order>> activeOrders(long marketplaceId) {
        _activeReads++;
        RuntimeException failure = _activeFailure;
        if (failure != null) throw failure;
        return _active.get();
    }

    @Override public Snapshot<List<Order>> recentTrades(long marketplaceId) {
        _marketplaceTradeReads++;
        return _recent.get();
    }
    @Override public Snapshot<List<Order>> recentTrades(long marketplaceId, int size) {
        _marketplaceTradeReads++;
        return _recent.get();
    }
    /** One market's legs out of the recent snapshot, as the server answers {@code ?market=}. */
    @Override public Snapshot<List<Order>> recentTrades(long marketplaceId, long marketId, int size) {
        _marketTradeReads.add(marketId + "/" + size);
        var all = _recent.get();
        return new Snapshot<>(all.body().stream().filter(o -> o.marketId() == marketId).toList(), all.asOfSeq());
    }

    private volatile int _marketplaceTradeReads = 0;
    private final List<String> _marketTradeReads = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The trade reads made market by market, each as {@code marketId/size}. */
    List<String> marketTradeReads() {
        return List.copyOf(_marketTradeReads);
    }

    /** The trade reads made for every market at once. */
    int marketplaceTradeReads() {
        return _marketplaceTradeReads;
    }

    @Override public Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue) {
        _queue.set(queue);
        return () -> _queue.set(null);
    }

    private final List<String> _submitted = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** What the desk sent on, each as {@code marketplace/market what}. */
    List<String> submitted() {
        return List.copyOf(_submitted);
    }

    @Override public Order submitLimit(long mp, long marketId, OrderSide side, long units, long price) {
        _submitted.add(mp + "/" + marketId + " " + side + " " + units + "@" + price);
        return new Order(null, null, 900L, 900L, 900L, null, OrderType.LIMIT, side, units, price,
                         null, null, mp, 1L, null, marketId, null, null);
    }

    @Override public Order submitCancel(long mp, long marketId, long originalId) {
        _submitted.add(mp + "/" + marketId + " CANCEL " + originalId);
        return new Order(null, null, 901L, 901L, originalId, null, OrderType.CANCEL, OrderSide.BUY, 0, 0,
                         null, null, mp, 1L, null, marketId, null, null);
    }

    @Override public void close() { }

    // --- everything else: a test that reaches here is told ------------------

    private static ApiException _no(String method) {
        return new ApiException("FakeFlexemarkets does not model " + method);
    }

    @Override public Account account() { throw _no("account"); }
    @Override public long accountId() { throw _no("accountId"); }
    @Override public String accountName() { throw _no("accountName"); }
    @Override public Person user() { throw _no("user"); }
    @Override public long userId() { throw _no("userId"); }
    @Override public Token token() { throw _no("token"); }
    @Override public long endpointMarketplaceId() { throw _no("endpointMarketplaceId"); }
    @Override public String endpointUrl() { throw _no("endpointUrl"); }

    @Override public List<Marketplace> marketplaces() { throw _no("marketplaces"); }
    @Override public Marketplace marketplace(long id) { throw _no("marketplace"); }
    @Override public List<String> symbols(long id) { throw _no("symbols"); }
    @Override public List<Session> sessions(long id) { throw _no("sessions"); }
    @Override public Session session(long id) { throw _no("session"); }
    @Override public List<Order> orders(long id) { throw _no("orders"); }
    @Override public List<Order> orders(long id, List<Long> sessionIds) { throw _no("orders"); }
    @Override public List<Order> orders(long id, String symbol) { throw _no("orders"); }
    @Override public List<Order> trades(long id, String symbol) { throw _no("trades"); }
    @Override public List<Holding> holdings(long id) { throw _no("holdings"); }
    @Override public List<Holding> holdings(long id, List<Long> sessionIds) { throw _no("holdings"); }
    @Override public Holding holding(long id) { throw _no("holding"); }
    @Override public String downloadHoldings(long id) { throw _no("downloadHoldings"); }
    @Override public String downloadHoldings(long id, List<Long> sessionIds) { throw _no("downloadHoldings"); }
    @Override public List<Allotment> allotments(long id, long allocationId) { throw _no("allotments"); }
    @Override public List<Person> users() { throw _no("users"); }
    @Override public List<ClientConnection> connections(long id) { throw _no("connections"); }

    @Override public Order submitLimit(long mp, long marketId, OrderSide side, long units, long price, Long ownerTargetId) { throw _no("submitLimit"); }
    @Override public Order submitMarket(long mp, long marketId, OrderSide side, long units) { throw _no("submitMarket"); }

    @Override public Session openSession(long id) { throw _no("openSession"); }
    @Override public Session pauseSession(long id) { throw _no("pauseSession"); }
    @Override public Session closeSession(long id) { throw _no("closeSession"); }
    @Override public Marketplace createMarketplaceFromJson(String json) { throw _no("createMarketplaceFromJson"); }
    @Override public List<Holding> allocate(long id, List<Holding> holdings) { throw _no("allocate"); }
    @Override public List<Holding> uploadHoldings(long id, Path csv) { throw _no("uploadHoldings"); }
    @Override public List<ParticipantState> uploadState(long id, Path csv) { throw _no("uploadState"); }
    @Override public List<Widget> pushWidgets(long id, List<WidgetPush> widgets) { throw _no("pushWidgets"); }
    @Override public boolean removeWidget(long id, String key) { throw _no("removeWidget"); }
    @Override public boolean removeWidget(long id, String key, long userId) { throw _no("removeWidget"); }
    @Override public List<Widget> allWidgets(long id) { throw _no("allWidgets"); }

    @Override public Token signup(String a, String e, String p) { throw _no("signup"); }
    @Override public Token signup(String a, String e, String p, String first, String last) { throw _no("signup"); }
    @Override public Person createUser(String e, String p, String first, String last, String... roles) { throw _no("createUser"); }
    @Override public Market createMarket(long mp, String symbol, String name, TickGrid price, TickGrid units, boolean privateMarket) { throw _no("createMarket"); }
    @Override public Account approveAccount(String accountName) { throw _no("approveAccount"); }
    @Override public Account accountById(long id) { throw _no("accountById"); }
    @Override public Person userById(long id) { throw _no("userById"); }
    @Override public List<String> identifiers(long id) { throw _no("identifiers"); }
    @Override public void deleteMyAccount() { throw _no("deleteMyAccount"); }
    @Override public List<Account> accounts() { throw _no("accounts"); }
    @Override public void deleteAccount(long id) { throw _no("deleteAccount"); }
    @Override public void deleteUser(long id) { throw _no("deleteUser"); }
    @Override public void deleteMarketplace(long id) { throw _no("deleteMarketplace"); }
    @Override public ManagerOtpBundle managerOtpBundle(List<Long> userIds) { throw _no("managerOtpBundle"); }

    @Override public void listen(long id, BlockingQueue<Object> queue) { throw _no("listen"); }
    @Override public void reconnect() { throw _no("reconnect"); }
    @Override public Desk desk(long id) { throw _no("desk"); }
}
