package fm.study;

import java.util.List;
import java.util.Map;

/**
 * A marketplace the study needs, before it exists.
 *
 * @param key           the study's own name for it -- {@code private},
 *                      {@code public} -- which its rotations refer to
 * @param name          the marketplace's name as created
 * @param description   its description
 * @param markets       what it trades
 * @param configuration marketplace configuration, {@code fm.view} among it;
 *                      a value is a string or a JSON document
 */
public record MarketplaceSpec(
        String key,
        String name,
        String description,
        List<MarketSpec> markets,
        Map<String, Object> configuration) {

    /** A marketplace needs a key and something to trade. */
    public MarketplaceSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a marketplace spec needs a key");
        }
        markets = markets == null ? List.of() : List.copyOf(markets);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        if (markets.isEmpty()) {
            throw new IllegalArgumentException("marketplace '" + key + "' needs at least one market");
        }
    }
}
