/**
 * The Flexemarkets SDK.
 *
 * <p>One package is exported: {@code fm}. Everything under {@code fm.internal}
 * is implementation -- it was public only because the classpath gave no way to
 * say otherwise, and a caller could import {@code HttpFlexemarkets} or
 * {@code DefaultDesk} and bind to a shape that is free to change. On the
 * module path they can no longer reach it.
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
    exports fm.model;
    exports fm.role;
    exports fm.event;

    opens fm to tools.jackson.databind;
    opens fm.model to tools.jackson.databind;
    opens fm.event to tools.jackson.databind;
    opens fm.internal to tools.jackson.databind;

    uses fm.FlexemarketsProvider;
}
