package fm.internal;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiRoot(@JsonProperty("_links") Map<String, LinkObject> links) {
    /**
     * HAL sends {@code templated} alongside {@code href}, and may send more.
     * Annotated like every other type here so the record survives a
     * consumer's own ObjectMapper: the SDK's mapper disables
     * FAIL_ON_UNKNOWN_PROPERTIES globally, but a published type should not
     * depend on the configuration of whoever reads it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LinkObject(String href) {}

    /**
     * The component is {@code links}; the wire name is {@code _links}.
     *
     * <p>The server speaks HAL, which spells the link envelope with a
     * leading underscore, and that is not a name a Java accessor should
     * carry. {@code @JsonProperty} keeps the binding exact in both
     * directions, so this reads as {@code links()} while still consuming
     * and producing {@code _links}. The Python and TypeScript SDKs already
     * expose it as {@code links}; this brings Java into line without
     * changing a byte of what crosses the network.
     */
    public Optional<String> getLink(String name) {
        if (links == null) return Optional.empty();
        var link = links.get(name);
        return link != null ? Optional.of(link.href()) : Optional.empty();
    }
}
