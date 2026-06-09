package fm.ticker;

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

import fm.Events.WsTransportError;
import fm.Flexemarkets;
import fm.MarketplaceTrades;
import fm.OrderBook;
import fm.OrderBooks;
import fm.OrdersUpdate;
import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Session;

public class Ticker {
    private static final int TRADE_DISPLAY_COUNT = 5;

    private final String credential;
    private final String endpoint;

    private OrderBooks orderBooks;
    private MarketplaceTrades trades;
    private List<Market> markets;

    private Session session;
    private Screen screen;
    private String endpointUrl;

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
        this.credential = credential;
        this.endpoint = endpoint;
    }

    void run() throws Exception {
        var queue = new ArrayBlockingQueue<>(1000);
        var events = new ArrayList<>();

        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        screen = new TerminalScreen(terminal);
        screen.startScreen();
        screen.setCursorPosition(null); // hide cursor

        try (var fm = Flexemarkets.connect(credential, endpoint, "fm-ticker")) {
            endpointUrl = fm.endpointUrl();
            var marketplaceId = fm.endpointMarketplaceId();

            markets = fm.markets(marketplaceId);
            markets.sort(Comparator.comparingLong(Market::id));

            session = fm.session(marketplaceId);

            orderBooks = new OrderBooks(markets);
            trades = new MarketplaceTrades(markets, 10);

            fm.listen(marketplaceId, queue);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                fm.close();
                stopScreen();
            }));

            display();

            while (!Session.STATE_CLOSED.equals(session != null ? session.state() : null)) {
                if (0 < queue.drainTo(events)) {
                    boolean redraw = false;
                    for (var event : events) {
                        switch (event) {
                            case Session s -> {
                                session = s;
                                redraw = true;
                            }
                            case Session[] list -> {
                                for (var s : list) session = s;
                                redraw = true;
                            }
                            case OrdersUpdate update -> {
                                orderBooks.update(update.orders());
                                trades.update(update.orders());
                                redraw = true;
                            }
                            case Holding _ -> { }
                            case WsTransportError _ -> {
                                fm.reconnect();
                            }
                            default -> { }
                        }
                    }
                    events.clear();
                    if (redraw) display();

                    if (Session.STATE_CLOSED.equals(session != null ? session.state() : null)) {
                        display();
                        break;
                    }
                }
                Thread.onSpinWait();
            }
        } finally {
            stopScreen();
        }
    }

    private void stopScreen() {
        if (screen != null) {
            try {
                screen.stopScreen();
            } catch (IOException ignored) {}
            screen = null;
        }
    }

    // -- display -------------------------------------------------------------

    private void display() throws IOException {
        screen.clear();
        var g = screen.newTextGraphics();

        var state = session != null ? session.state() : "---";

        // Header
        g.putString(0, 0, "fm-ticker  " + (endpointUrl != null ? endpointUrl : ""));
        g.putString(50, 1, state);

        // Column headers
        int row = 3;
        putRight(g, 8,  row, "Symbol");
        putRight(g, 16, row, "Bid");
        putRight(g, 24, row, "Ask");
        putRight(g, 32, row, "Spread");
        g.putString(36, row, "Last trades");

        row++;
        putRight(g, 8,  row, "------");
        putRight(g, 16, row, "------");
        putRight(g, 24, row, "------");
        putRight(g, 32, row, "------");
        g.putString(36, row, "-----------");

        // Market rows
        row++;
        var sorted = orderBooks.collection().stream()
                .sorted(Comparator.comparingLong(OrderBook::marketId)).toList();
        for (var book : sorted) {
            var bid = book.bestBuyPrice();
            var ask = book.bestSellPrice();
            var symbol = book.symbol();

            var marketTrades = trades.collection().stream()
                    .filter(t -> t.marketId() == book.marketId())
                    .findFirst().orElse(null);
            var recentPrices = marketTrades != null ? marketTrades.mostRecentPrices() : new long[0];

            putRight(g, 8,  row, symbol);
            putRight(g, 16, row, price(bid));
            putRight(g, 24, row, price(ask));
            putRight(g, 32, row, spread(bid, ask));
            g.putString(36, row, tradePrices(recentPrices, TRADE_DISPLAY_COUNT));

            row++;
        }

        screen.refresh();
    }

    private static void putRight(TextGraphics g, int col, int row, String text) {
        g.putString(new TerminalPosition(col - text.length(), row), text);
    }

    // -- formatting ----------------------------------------------------------

    private static String price(long cents) {
        if (cents < 0) return "     -";
        return "$%5.2f".formatted(cents / 100.0);
    }

    private static String spread(long bid, long ask) {
        if (bid < 0 || ask < 0) return "     -";
        return "$%5.2f".formatted((ask - bid) / 100.0);
    }

    private static String tradePrices(long[] prices, int count) {
        int start = Math.max(0, prices.length - count);
        var sb = new StringBuilder();
        for (int i = prices.length - 1; i >= start; i--) {
            if (!sb.isEmpty()) sb.append("  ");
            sb.append("$%.2f".formatted(prices[i] / 100.0));
        }
        return sb.toString();
    }
}
