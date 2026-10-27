package fm.ticker;

import fm.model.Market;
import fm.model.Session;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import fm.Decimals;
import fm.Flexemarkets;
import fm.Book;
import fm.Desk;

public class Ticker {
    private static final int TRADE_DISPLAY_COUNT = 5;

    private final String _credential;
    private final String _endpoint;

    private Desk _desk;
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

            // A desk keeps the books and the tape for us: seeded from the
            // REST snapshot, kept current from the same delta stream, and
            // reseeded on a sequence gap. That is the whole reason this
            // example no longer holds aggregators of its own.
            _desk = fm.desk(marketplaceId);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                fm.close();
                _stopScreen();
            }));

            _display();

            // The desk dispatches on its own thread, so these only flag that
            // something moved; the screen is drawn from the main loop below.
            // Lanterna is not thread-safe, and a redraw from the stream thread
            // would race the one down here.
            var dirty = new java.util.concurrent.atomic.AtomicBoolean(true);
            _desk.onSessionChange(s -> { _session = s; dirty.set(true); });
            for (var market : _markets) {
                _desk.onBookChange(market.id(), b -> dirty.set(true));
            }

            while (!Session.STATE_CLOSED.equals(_session != null ? _session.state() : null)) {
                if (dirty.compareAndSet(true, false)) {
                    _display();

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
        g.putString(36, row, "Last trades");

        row++;
        _putRight(g, 8,  row, "------");
        _putRight(g, 16, row, "------");
        _putRight(g, 24, row, "------");
        _putRight(g, 32, row, "------");
        g.putString(36, row, "-----------");

        // Market rows
        row++;
        var sorted = _desk.books().stream()
                .sorted(Comparator.comparingLong(Book::marketId)).toList();
        for (var book : sorted) {
            var bid = book.bestBuyPrice();
            var ask = book.bestSellPrice();
            var symbol = book.symbol();

            var marketTrades = _desk.tapes().stream()
                    .filter(t -> t.marketId() == book.marketId())
                    .findFirst().orElse(null);
            var recentPrices = marketTrades != null ? marketTrades.mostRecentPrices() : new long[0];

            _putRight(g, 8,  row, symbol);
            int decimals = book.market().priceDecimals();
            _putRight(g, 16, row, _price(bid, decimals));
            _putRight(g, 24, row, _price(ask, decimals));
            _putRight(g, 32, row, _spread(bid, ask, decimals));
            g.putString(36, row, _tradePrices(recentPrices, TRADE_DISPLAY_COUNT, decimals));

            row++;
        }

        _screen.refresh();
    }

    private static void _putRight(TextGraphics g, int col, int row, String text) {
        g.putString(new TerminalPosition(col - text.length(), row), text);
    }

    // -- formatting ----------------------------------------------------------

    // Every price is shown at its own market's priceDecimals.

    private static String _price(long price, int decimals) {
        if (price < 0) return "     -";
        return "%6s".formatted(Decimals.format(price, decimals));
    }

    private static String _spread(long bid, long ask, int decimals) {
        if (bid < 0 || ask < 0) return "     -";
        return "%6s".formatted(Decimals.format(ask - bid, decimals));
    }

    private static String _tradePrices(long[] prices, int count, int decimals) {
        int start = Math.max(0, prices.length - count);
        var sb = new StringBuilder();
        for (int i = prices.length - 1; i >= start; i--) {
            if (!sb.isEmpty()) sb.append("  ");
            sb.append(Decimals.format(prices[i], decimals));
        }
        return sb.toString();
    }
}
