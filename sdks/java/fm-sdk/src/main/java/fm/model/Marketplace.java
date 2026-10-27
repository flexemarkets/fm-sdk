package fm.model;

import java.util.List;

import fm.Decimals;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/**
 * A marketplace and the markets in it.
 *
 * @param id          the marketplace's id
 * @param name        its name
 * @param description its description
 * @param markets     the markets it contains; a marketplace cannot be
 *                    created without at least one
 * @param cashDecimals the decimal places cash, available cash, starting capital
 *                    and payoffs carry: at 2, cash of 5000 is 50.00; 2 when the
 *                    server does not say. {@link Decimals} converts
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record Marketplace(
    long id,
    String name,
    String description,
    List<Market> markets,
    Integer cashDecimals) {

    /** A marketplace from a server older than the cash scale counts cents. */
    public Marketplace {
        cashDecimals = null == cashDecimals ? Decimals.DEFAULT_CASH_DECIMALS : cashDecimals;
    }

    /**
     * A marketplace at the default cash scale, cents: the shape before the
     * cash scale, kept so code built against it (fm-robots' fakes construct
     * marketplaces this way) still compiles.
     *
     * @param id          the marketplace's id
     * @param name        its name
     * @param description its description
     * @param markets     the markets it contains
     */
    public Marketplace(long id, String name, String description, List<Market> markets) {
        this(id, name, description, markets, null);
    }
}
