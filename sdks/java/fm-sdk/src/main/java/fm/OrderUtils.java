package fm;

import fm.Types.Order;

public class OrderUtils {
    private OrderUtils() {}

    public static boolean isAvailable(Order order) {
        return order != null && order.consumer() == null;
    }

    public static boolean isConsumed(Order order) {
        if (order != null) {
            var consumer = order.consumer();
            return consumer != null && consumer.longValue() != 0;
        }
        return false;
    }

    public static boolean isSplit(Order order) {
        if (order != null) {
            var consumer = order.consumer();
            return consumer != null && consumer.longValue() == 0;
        }
        return false;
    }

    public static boolean isCancel(Order order) {
        return Order.TYPE_CANCEL.equalsIgnoreCase(order.type());
    }

    public static boolean isLimit(Order order) {
        return Order.TYPE_LIMIT.equalsIgnoreCase(order.type());
    }

    public static boolean isBuy(Order order) {
        return Order.SIDE_BUY.equalsIgnoreCase(order.side());
    }

    public static boolean isSell(Order order) {
        return Order.SIDE_SELL.equalsIgnoreCase(order.side());
    }

    public static boolean isBuy(String side) {
        return Order.SIDE_BUY.equalsIgnoreCase(side);
    }

    public static String contra(String side) {
        return Order.SIDE_BUY.equalsIgnoreCase(side) ? Order.SIDE_SELL : Order.SIDE_BUY;
    }

    public static boolean isSymbol(String symbol, Order order) {
        return symbol == null || symbol.equalsIgnoreCase(order.symbol());
    }

    public static Order findOrder(Order[] orders, Long id) {
        if (id == null) return null;
        for (var order : orders) {
            if (order.id() == id.longValue()) {
                return order;
            }
        }
        return null;
    }

    public static boolean isResting(Order[] orders, Order order) {
        // available order is resting
        if (isAvailable(order)) {
            return true;
        }

        // CANCEL order is not resting
        if (isCancel(order)) {
            return false;
        }

        // Cancelled LIMIT order is resting
        if (isCancelled(orders, order)) {
            return true;
        }

        // order with supplier not in trade-set is resting
        if (!inTradeSet(orders, order.supplier())) {
            return true;
        }

        // order supplier older than consumer supplier
        if (isTraded(orders, order)) {
            return isSupplierOlder(orders, order);
        }

        // split order with child younger than its consumer is resting
        return isSupplierOlder(orders, firstChild(orders, order));
    }

    // --- private helpers ---

    private static boolean isCancelled(Order[] orders, Order order) {
        var consumer = findOrder(orders, order.consumer());
        if (consumer != null) {
            return !order.type().equalsIgnoreCase(consumer.type());
        }
        return false;
    }

    private static boolean inTradeSet(Order[] orders, long id) {
        for (var order : orders) {
            if (order.id() == id) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTraded(Order[] orders, Order order) {
        if (isLimit(order) && isConsumed(order)) {
            var consumer = findOrder(orders, order.consumer());
            return consumer != null && isLimit(consumer);
        }
        return false;
    }

    private static boolean isSupplierOlder(Order[] orders, Order order) {
        if (order == null) return false;

        var orderSupplier = findOrder(orders, order.supplier());
        var consumer = findOrder(orders, order.consumer());

        Long consumerSupplierId = null;
        if (consumer != null) {
            consumerSupplierId = consumer.supplier();
        }

        var consumerSupplier = findOrder(orders, consumerSupplierId);
        return isOlder(orders, orderSupplier, consumerSupplier);
    }

    private static boolean isOlder(Order[] orders, Order o1, Order o2) {
        if (o1 == null) return true;
        if (o2 == null) return false;

        var o1InTradeSet = inTradeSet(orders, o1.original());
        var o2InTradeSet = inTradeSet(orders, o2.original());

        if (!o1InTradeSet && o2InTradeSet) return true;
        if (o1InTradeSet && !o2InTradeSet) return false;

        var o1original = o1;
        if (o1.id() != o1.original()) {
            o1original = findOrder(orders, o1.original());
        }

        var o2original = o2;
        if (o2.id() != o2.original()) {
            o2original = findOrder(orders, o2.original());
        }

        return isCreatedEarlier(o1original, o2original);
    }

    private static boolean isCreatedEarlier(Order order, Order consumer) {
        return order.id() < consumer.id();
    }

    private static Order firstChild(Order[] orders, Order order) {
        for (var o : orders) {
            if (order.id() == o.original() && !isSplit(o)) {
                return o;
            }
        }
        return null;
    }
}
