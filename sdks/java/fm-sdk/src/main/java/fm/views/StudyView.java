package fm.views;

import java.util.Objects;

/**
 * A study's trading view: the {@code fm.view} document a marketplace
 * running the study carries, as a template with the market's symbol left
 * to fill in.
 *
 * <p>The template is JSON text with {@code {{symbol}}} where an expression
 * names the study's one market -- {@code now.units.{{symbol}}} -- so the
 * view reads whatever market the marketplace actually has. {@link #render}
 * fills it in; nothing else in the document varies by marketplace.
 *
 * @param id          the study's short name, as its CLI is named: {@code smith62}
 * @param label       what a menu shows
 * @param description what the view puts on the screen, in a sentence
 * @param template    the document, with the placeholder in
 */
public record StudyView(String id, String label, String description, String template) {
    /** The placeholder a template carries where the market's symbol goes. */
    public static final String SYMBOL = "{{symbol}}";

    /** Refuses a view with any part missing: each is read by a menu or a renderer. */
    public StudyView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(template, "template");
    }

    /**
     * The document for a marketplace whose market is {@code symbol}.
     *
     * @param symbol the market's symbol
     * @return the template with the placeholder replaced
     */
    public String render(String symbol) {
        return template.replace(SYMBOL, Objects.requireNonNull(symbol, "symbol"));
    }
}
