package fm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forms an endpoint may take, and what they mean.
 *
 * <p>An endpoint reaches the SDK in one of several shapes: a bare marketplace
 * id, a path to a file holding one, a URL, or something only a
 * {@link FlexemarketsProvider} understands. They all denote the same thing —
 * one marketplace, somewhere — and this is the single place that says so.
 *
 * <p>It exists because provider selection has to happen against the
 * <em>resolved</em> endpoint, not the argument as typed. Asking providers about
 * the raw string meant a file containing a provider's endpoint could never be
 * claimed: it would fall through to HTTP, which would read the file, find an
 * endpoint it could not dial, and fail somewhere far from the cause.
 */
public final class Endpoints {

    private static final Pattern MARKETPLACE_ID = Pattern.compile("^\\d+$");

    /** The trailing id in {@code .../marketplaces/1744}, and in {@code loopback:1744}. */
    private static final Pattern TRAILING_ID = Pattern.compile("(?:^|[/:])(\\d+)/?$");

    private Endpoints() {
    }

    /**
     * Resolve an endpoint to the form the rest of the SDK works with.
     *
     * <p>A bare id becomes the marketplace's URL on the default host; a file
     * becomes whatever endpoint it holds; anything else is returned untouched.
     *
     * <p>Untouched rather than rejected, deliberately. A form this class does
     * not recognise may be one a provider does, and refusing here would decide
     * that question before the providers are asked. A genuine typo still fails,
     * a moment later, when nothing claims it and the HTTP client rejects it.
     */
    public static String resolve(String endpoint) throws IOException {
        if (null == endpoint || endpoint.isBlank()) {
            return endpoint;
        }

        String trimmed = endpoint.trim();

        if (MARKETPLACE_ID.matcher(trimmed).matches()) {
            return marketplaceUrl(trimmed);
        }

        Path path = asPath(trimmed);
        if (null != path && Files.isRegularFile(path)) {
            String fromFile = endpointIn(path);
            return null == fromFile ? trimmed : fromFile.trim();
        }

        return trimmed;
    }

    /**
     * The marketplace an endpoint names, in any of its forms.
     *
     * <p>One place, so a provider does not have to re-derive it from a shape it
     * only half understands.
     */
    public static long marketplaceId(String endpoint) {
        if (null == endpoint) {
            throw new IllegalArgumentException("An endpoint names its marketplace; got: null");
        }

        String trimmed = endpoint.trim();
        if (MARKETPLACE_ID.matcher(trimmed).matches()) {
            return Long.parseLong(trimmed);
        }

        Matcher matcher = TRAILING_ID.matcher(trimmed);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }

        throw new IllegalArgumentException("An endpoint names its marketplace; got: " + endpoint);
    }

    /** The default host, overridable for development. */
    static String marketplaceUrl(String marketplaceId) {
        String host = System.getenv("FM_URL");
        return (null == host ? "https://adhocmarkets.com" : host) + "/api/marketplaces/" + marketplaceId;
    }

    private static String endpointIn(Path path) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties.getProperty("endpoint");
    }

    /**
     * Null when the string cannot be a path at all.
     *
     * <p>{@code Path.of} throws on some inputs a provider might legitimately
     * use — a NUL byte, on some platforms a colon — and an endpoint form this
     * class does not recognise must not become an exception here.
     */
    private static Path asPath(String candidate) {
        try {
            return Path.of(candidate);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
