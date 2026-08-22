package fm;


/**
 * What can only be worked out from a set of orders together.
 *
 * <p>Whether an order rests, was split, or was consumed is a question about
 * its relationships — the exchange expresses a trade as several orders
 * referring to each other — so it cannot be answered by the order alone and
 * belongs here rather than on {@link Order}.
 *
 * <p>isBuy, isSell, isCancel and isLimit used to live here too. They became
 * one-line comparisons the moment side and type became enums: {@code
 * Side.BUY == order.side()} says the same thing as {@code isBuy(order)},
 * reads the same way, and is one fewer name to know. Two ways to ask one
 * question is one too many.
 */
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

    public static boolean isSymbol(String symbol, Order order) {
        return symbol == null || symbol.equalsIgnoreCase(order.symbol());
    }

    /**
     * Whether the order is one somebody submitted, rather than one the exchange
     * produced by splitting or matching it.
     *
     * <p>A submitted order is its own original and its own supplier; a split or
     * a trade carries the id of the order it came from. A cancel counts as a
     * submission — somebody sent it — even though it names the order it cancels.
     *
     * <p>Python and TypeScript have had this since the order utilities landed;
     * Java had not, so a Java caller filtering a session's orders down to what
     * participants actually did had to spell the identity check out by hand.
     */
    public static boolean isSubmit(Order order) {
        return OrderType.CANCEL == order.type()
            || (order.id() == order.original() && order.id() == order.supplier());
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
        if (OrderType.CANCEL == order.type()) {
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
            return order.type() != consumer.type();
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
        if (OrderType.LIMIT == order.type() && isConsumed(order)) {
            var consumer = findOrder(orders, order.consumer());
            return consumer != null && OrderType.LIMIT == consumer.type();
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
