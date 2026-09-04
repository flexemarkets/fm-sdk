/**
 * The Flexemarkets SDK.
 *
 * <p>Five packages are exported, split by what you do with what is in them
 * rather than by subject. {@code fm} was flat until 0.2.0 -- fifty-two types in
 * one package, where the things you receive sat beside the things you call and
 * the things you catch.
 *
 * <ul>
 *   <li>{@code fm} -- what you hold and call: {@link fm.Flexemarkets},
 *       {@link fm.Desk}, {@link fm.Book}, {@link fm.Tape},
 *       {@link fm.Subscription}, {@link fm.Snapshot}, {@link fm.Orders} and
 *       {@link fm.Endpoints}.
 *   <li>{@code fm.model} -- what the server sends and what you hand back: the
 *       wire records, plus {@link fm.model.OrderSide}, {@link fm.model.OrderType}
 *       and {@link fm.model.TickGrid}.
 *   <li>{@code fm.error} -- what you catch, all of it under
 *       {@link fm.error.FlexemarketsException}.
 *   <li>{@code fm.event} -- what arrives on the queue passed to
 *       {@code listen}.
 * </ul>
 *
 * <p>{@code fm.role} is <em>not</em> exported. The six interfaces
 * {@link fm.Flexemarkets} composes are still what shapes it -- every method it
 * has belongs to one of them, which is what makes "every role method is
 * abstract" a property rather than a habit -- but they are not names a caller
 * writes. They were exported through 0.1.x so a signature could narrow to one,
 * and nothing ever did: fm-robots, fm-server and fm-robots-server import zero
 * of them. Withdrawing them while that is still true costs nobody a call site.
 * Re-exporting later is additive if a caller ever wants to narrow.
 *
 * <p>Everything under {@code fm.internal} is implementation -- it was public
 * only because the classpath gave no way to say otherwise, and a caller could
 * import {@code HttpFlexemarkets} or {@code DefaultDesk} and bind to a shape
 * that is free to change. On the module path they can no longer reach it.
 *
 * <p>{@code fm.internal} is <em>opened</em> to Jackson rather than exported:
 * opening grants reflective access for binding and nothing else, so the wire
 * records stay bindable while remaining unreadable to callers.
 */
module fm {
    requires java.net.http;
    requires tools.jackson.core;
    requires tools.jackson.databind;
    requires com.fasterxml.jackson.annotation;

    exports fm;
    exports fm.error;
    exports fm.event;
    exports fm.model;

    opens fm to tools.jackson.databind;
    opens fm.model to tools.jackson.databind;
    opens fm.event to tools.jackson.databind;
    opens fm.internal to tools.jackson.databind;

    uses fm.FlexemarketsProvider;
}
