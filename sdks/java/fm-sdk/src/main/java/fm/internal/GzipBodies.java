package fm.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * A response body as text, gunzipped when the server gzipped it.
 *
 * <p>The SDK asks for gzip (fm-server compresses JSON for a client that
 * asks: a marketplace read is 39 KB, 5.5 KB gzipped -- MELBOURNE-LATENCY
 * F14), and java.net.http does not decode it: asked for and not decoded,
 * every answer would read as binary. So decoding sits here, beside the
 * asking, and a server that answers plain is read as before.
 */
final class GzipBodies {

    static final String ACCEPT_ENCODING = "Accept-Encoding";
    static final String GZIP = "gzip";

    private GzipBodies() {
    }

    static HttpResponse.BodyHandler<String> ofString() {
        return info -> {
            boolean gzipped = info.headers().firstValue("Content-Encoding")
                    .map(v -> v.trim().toLowerCase(Locale.ROOT).equals(GZIP)).orElse(false);
            Charset charset = _charset(info.headers().firstValue("Content-Type").orElse(""));
            return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray(),
                    bytes -> gzipped ? _gunzip(bytes, charset) : new String(bytes, charset));
        };
    }

    static String _gunzip(byte[] bytes, Charset charset) {
        if (bytes.length == 0) {
            return "";
        }
        try (var in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), charset);
        } catch (IOException e) {
            throw new UncheckedIOException("a gzipped response that would not decompress", e);
        }
    }

    /** The charset a Content-Type names, UTF-8 when it names none or one we cannot use. */
    static Charset _charset(String contentType) {
        for (String part : contentType.split(";")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(p.substring("charset=".length()).replace("\"", "").trim());
                } catch (RuntimeException unknown) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
