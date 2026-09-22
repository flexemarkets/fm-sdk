package fm.views;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The study views the SDK ships -- the one place they live.
 *
 * <p>A study's view is read by two things that never share a process: the
 * study's CLI, which creates the marketplace with it, and the server, which
 * offers it to a manager as something to start from. Each used to keep its
 * own copy, and the two drifted. Both depend on this jar, so this is where
 * the document is, and {@code fm/views/index.json} beside it is the list.
 */
public final class StudyViews {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<StudyView> ALL = load();

    private StudyViews() {}

    /** Every study view shipped, in the index's order. */
    public static List<StudyView> all() {
        return ALL;
    }

    /** The view for the study named {@code id}, if one is shipped. */
    public static Optional<StudyView> of(String id) {
        return ALL.stream().filter(view -> view.id().equals(id)).findFirst();
    }

    private static List<StudyView> load() {
        JsonNode index = JSON.readTree(resource("index.json"));
        return index.valueStream()
                .map(entry -> new StudyView(
                        entry.path("id").asString(),
                        entry.path("label").asString(),
                        entry.path("description").asString(),
                        resource(entry.path("resource").asString())))
                .toList();
    }

    private static String resource(String name) {
        try (InputStream in = StudyViews.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("fm/views/" + name + " is not in the SDK");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
