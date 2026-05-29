package fm;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fm.Types.Market;
import fm.Types.Order;

public class OrderBooks {
    private final Map<Long, OrderBook> books = new ConcurrentHashMap<>();

    public OrderBooks(List<Market> markets) {
        for (var market : markets) {
            books.put(market.id(), new OrderBook(market));
        }
    }

    public void update(Order[] orders) {
        books.values().forEach(b -> b.update(orders));
    }

    public OrderBook get(long marketId) {
        return books.get(marketId);
    }

    public Collection<OrderBook> collection() {
        return books.values();
    }

    /** Clear every contained book — see {@link OrderBook#clear()}. */
    public void clear() {
        books.values().forEach(OrderBook::clear);
    }
}
