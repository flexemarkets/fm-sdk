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
 * OrderSide.BUY == order.side()} says the same thing as {@code isBuy(order)},
 * reads the same way, and is one fewer name to know. Two ways to ask one
 * question is one too many.
 */
public class OrderUtils {
    private OrderUtils() {}

    /**
     * Whether the order still rests unconsumed.
     *
     * @param order the order to test; null answers false
     * @return true if nothing has consumed it
     */
    public static boolean isAvailable(Order order) {
        return order != null && order.consumer() == null;
    }

    /**
     * Whether the order was traded against.
     *
     * <p>Distinct from {@link #isSplit}: both carry a consumer, but a split
     * marker's is zero. The two are the reason a plain null check on
     * {@code consumer()} answers the wrong question.
     *
     * @param order the order to test; null answers false
     * @return true if it has a consumer other than zero
     */
    public static boolean isConsumed(Order order) {
        if (order != null) {
            var consumer = order.consumer();
            return consumer != null && consumer.longValue() != 0;
        }
        return false;
    }

    /**
     * Whether the order is the marker left behind when one was split.
     *
     * @param order the order to test; null answers false
     * @return true if its consumer is zero
     */
    public static boolean isSplit(Order order) {
        if (order != null) {
            var consumer = order.consumer();
            return consumer != null && consumer.longValue() == 0;
        }
        return false;
    }

    /**
     * Whether the order belongs to a market, by symbol.
     *
     * @param symbol the symbol to match, case-insensitively; null matches
     *               everything, which is what lets a caller pass "no filter"
     * @param order  the order to test
     * @return true if the order carries that symbol
     */
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
     *
     * @param order the order to test
     * @return true if somebody submitted it
     */
    public static boolean isSubmit(Order order) {
        return OrderType.CANCEL == order.type()
            || (order.id() == order.original() && order.id() == order.supplier());
    }

    /**
     * The order with a given id, if it is in the array.
     *
     * @param orders the orders to search
     * @param id     the id to look for; null answers null, since an absent
     *               consumer or supplier is spelled that way
     * @return the matching order, or null if there is none
     */
    public static Order findOrder(Order[] orders, Long id) {
        if (id == null) return null;
        for (var order : orders) {
            if (order.id() == id.longValue()) {
                return order;
            }
        }
        return null;
    }

    /**
     * Whether the order is still on the book, judged against its neighbours.
     *
     * <p>Needs the whole array because resting is a property of an order's
     * relationships rather than of the order alone: a consumed order may have
     * left a remainder that is itself resting, and only the other orders say so.
     *
     * @param orders the orders the judgement is made against
     * @param order  the order to test
     * @return true if it, or the remainder it left, is still available
     */
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
        if (_isCancelled(orders, order)) {
            return true;
        }

        // order with supplier not in trade-set is resting
        if (!_inTradeSet(orders, order.supplier())) {
            return true;
        }

        // order supplier older than consumer supplier
        if (_isTraded(orders, order)) {
            return _isSupplierOlder(orders, order);
        }

        // split order with child younger than its consumer is resting
        return _isSupplierOlder(orders, _firstChild(orders, order));
    }

    // --- private helpers ---

    private static boolean _isCancelled(Order[] orders, Order order) {
        var consumer = findOrder(orders, order.consumer());
        if (consumer != null) {
            return order.type() != consumer.type();
        }
        return false;
    }

    private static boolean _inTradeSet(Order[] orders, long id) {
        for (var order : orders) {
            if (order.id() == id) {
                return true;
            }
        }
        return false;
    }

    private static boolean _isTraded(Order[] orders, Order order) {
        if (OrderType.LIMIT == order.type() && isConsumed(order)) {
            var consumer = findOrder(orders, order.consumer());
            return consumer != null && OrderType.LIMIT == consumer.type();
        }
        return false;
    }

    private static boolean _isSupplierOlder(Order[] orders, Order order) {
        if (order == null) return false;

        var orderSupplier = findOrder(orders, order.supplier());
        var consumer = findOrder(orders, order.consumer());

        Long consumerSupplierId = null;
        if (consumer != null) {
            consumerSupplierId = consumer.supplier();
        }

        var consumerSupplier = findOrder(orders, consumerSupplierId);
        return _isOlder(orders, orderSupplier, consumerSupplier);
    }

    private static boolean _isOlder(Order[] orders, Order o1, Order o2) {
        if (o1 == null) return true;
        if (o2 == null) return false;

        var o1InTradeSet = _inTradeSet(orders, o1.original());
        var o2InTradeSet = _inTradeSet(orders, o2.original());

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

        return _isCreatedEarlier(o1original, o2original);
    }

    private static boolean _isCreatedEarlier(Order order, Order consumer) {
        return order.id() < consumer.id();
    }

    private static Order _firstChild(Order[] orders, Order order) {
        for (var o : orders) {
            if (order.id() == o.original() && !isSplit(o)) {
                return o;
            }
        }
        return null;
    }
}
