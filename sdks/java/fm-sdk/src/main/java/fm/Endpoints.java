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

    /** Where the API is served. The website is on the apex; this is not it. */
    public static final String DEFAULT_HOST = "https://api.flexemarkets.com";

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
     *
     * @param endpoint a URL, a bare marketplace id, or a path to a file
     *                 holding either; null and blank pass through
     * @return the endpoint it denotes, or the input unchanged if this class
     *         does not recognise the form
     * @throws IOException if the endpoint names a file that cannot be read
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
            if (null == fromFile) {
                return trimmed;
            }

            // A bare id is expanded here too. It used to be expanded only when
            // it arrived as the argument, so "endpoint=1234" in ~/.fm/endpoint
            // reached the HTTP client as "1234" and failed a long way from the
            // cause. Only the id form is re-resolved -- a file naming another
            // file is not followed, so this cannot loop.
            String value = fromFile.trim();
            return MARKETPLACE_ID.matcher(value).matches() ? marketplaceUrl(value) : value;
        }

        return trimmed;
    }

    /**
     * The marketplace an endpoint names, in any of its forms.
     *
     * <p>One place, so a provider does not have to re-derive it from a shape it
     * only half understands.
     *
     * @param endpoint a URL or a bare marketplace id
     * @return the marketplace it names
     * @throws IllegalArgumentException if it names none
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

    /**
     * The default host, overridable for development.
     *
     * <p>The API is on {@code api.}. This defaulted to the apex
     * {@code https://adhocmarkets.com}, which is the website: it answers
     * {@code /api/marketplaces/1234} with 200 and a page of HTML, so a bare id
     * failed while parsing that as JSON rather than saying it could not reach
     * the API.
     */
    static String marketplaceUrl(String marketplaceId) {
        String host = System.getenv("FM_URL");
        return (null == host ? DEFAULT_HOST : host) + "/api/marketplaces/" + marketplaceId;
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
