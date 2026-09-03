package fm.ticker;

import fm.event.OrdersUpdate;
import fm.event.StreamDropped;
import fm.model.Holding;
import fm.model.Market;
import fm.model.Session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import fm.Flexemarkets;
import fm.MarketBook;
import fm.MarketplaceTrades;
import fm.MarketplaceBooks;

public class Ticker {
    private static final int TRADE_DISPLAY_COUNT = 5;

    private final String _credential;
    private final String _endpoint;

    private MarketplaceBooks _orderBooks;
    private MarketplaceTrades _trades;
    private List<Market> _markets;

    private Session _session;
    private Screen _screen;
    private String _endpointUrl;

    public static void main(String[] args) throws Exception {
        String credential = null;
        String endpoint = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-C" -> credential = args[++i];
                case "-E" -> endpoint = args[++i];
            }
        }

        new Ticker(credential, endpoint).run();
    }

    Ticker(String credential, String endpoint) {
        this._credential = credential;
        this._endpoint = endpoint;
    }

    void run() throws Exception {
        var queue = new ArrayBlockingQueue<>(1000);
        var events = new ArrayList<>();

        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        _screen = new TerminalScreen(terminal);
        _screen.startScreen();
        _screen.setCursorPosition(null); // hide cursor

        try (var fm = Flexemarkets.connect(_credential, _endpoint, "fm-ticker")) {
            _endpointUrl = fm.endpointUrl();
            var marketplaceId = fm.endpointMarketplaceId();

            _markets = fm.markets(marketplaceId);
            _markets.sort(Comparator.comparingLong(Market::id));

            _session = fm.session(marketplaceId);

            _orderBooks = new MarketplaceBooks(_markets);
            _trades = new MarketplaceTrades(_markets, 10);

            fm.listen(marketplaceId, queue);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                fm.close();
                _stopScreen();
            }));

            _display();

            while (!Session.STATE_CLOSED.equals(_session != null ? _session.state() : null)) {
                if (0 < queue.drainTo(events)) {
                    boolean redraw = false;
                    for (var event : events) {
                        switch (event) {
                            case Session s -> {
                                _session = s;
                                redraw = true;
                            }
                            case Session[] list -> {
                                for (var s : list) _session = s;
                                redraw = true;
                            }
                            case OrdersUpdate update -> {
                                _orderBooks.update(update.orders());
                                _trades.update(update.orders());
                                redraw = true;
                            }
                            case Holding ignored -> { }
                            case StreamDropped ignored -> {
                                fm.reconnect();
                            }
                            default -> { }
                        }
                    }
                    events.clear();
                    if (redraw) _display();

                    if (Session.STATE_CLOSED.equals(_session != null ? _session.state() : null)) {
                        _display();
                        break;
                    }
                }
                Thread.onSpinWait();
            }
        } finally {
            _stopScreen();
        }
    }

    private void _stopScreen() {
        if (_screen != null) {
            try {
                _screen.stopScreen();
            } catch (IOException ignored) {}
            _screen = null;
        }
    }

    // -- display -------------------------------------------------------------

    private void _display() throws IOException {
        _screen.clear();
        var g = _screen.newTextGraphics();

        var state = _session != null ? _session.state() : "---";

        // Header
        g.putString(0, 0, "fm-ticker  " + (_endpointUrl != null ? _endpointUrl : ""));
        g.putString(50, 1, state);

        // Column headers
        int row = 3;
        _putRight(g, 8,  row, "Symbol");
        _putRight(g, 16, row, "Bid");
        _putRight(g, 24, row, "Ask");
        _putRight(g, 32, row, "Spread");
        g.putString(36, row, "Last _trades");

        row++;
        _putRight(g, 8,  row, "------");
        _putRight(g, 16, row, "------");
        _putRight(g, 24, row, "------");
        _putRight(g, 32, row, "------");
        g.putString(36, row, "-----------");

        // Market rows
        row++;
        var sorted = _orderBooks.collection().stream()
                .sorted(Comparator.comparingLong(MarketBook::marketId)).toList();
        for (var book : sorted) {
            var bid = book.bestBuyPrice();
            var ask = book.bestSellPrice();
            var symbol = book.symbol();

            var marketTrades = _trades.collection().stream()
                    .filter(t -> t.marketId() == book.marketId())
                    .findFirst().orElse(null);
            var recentPrices = marketTrades != null ? marketTrades.mostRecentPrices() : new long[0];

            _putRight(g, 8,  row, symbol);
            _putRight(g, 16, row, _price(bid));
            _putRight(g, 24, row, _price(ask));
            _putRight(g, 32, row, _spread(bid, ask));
            g.putString(36, row, _tradePrices(recentPrices, TRADE_DISPLAY_COUNT));

            row++;
        }

        _screen.refresh();
    }

    private static void _putRight(TextGraphics g, int col, int row, String text) {
        g.putString(new TerminalPosition(col - text.length(), row), text);
    }

    // -- formatting ----------------------------------------------------------

    private static String _price(long cents) {
        if (cents < 0) return "     -";
        return "$%5.2f".formatted(cents / 100.0);
    }

    private static String _spread(long bid, long ask) {
        if (bid < 0 || ask < 0) return "     -";
        return "$%5.2f".formatted((ask - bid) / 100.0);
    }

    private static String _tradePrices(long[] prices, int count) {
        int start = Math.max(0, prices.length - count);
        var sb = new StringBuilder();
        for (int i = prices.length - 1; i >= start; i--) {
            if (!sb.isEmpty()) sb.append("  ");
            sb.append("$%.2f".formatted(prices[i] / 100.0));
        }
        return sb.toString();
    }
}
