package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Executable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.junit.jupiter.api.Test;

/**
 * The hand-written creators cannot drift from the records they build.
 *
 * <p>A record carrying an {@link Instant} needs a {@code @JsonCreator} taking
 * the wire's String, because Jackson cannot make an Instant out of
 * {@code "2017-04-11T00:54:35.135"} on its own. That creator repeats the
 * component list by hand -- eighteen of them on Order -- and nothing in the
 * language ties the two together.
 *
 * <p>So the failure is silent in both directions. Add a component and forget
 * the creator and the field is quietly null on every response; add an Instant
 * component with no creator at all and the same. Neither breaks a build, and
 * neither breaks any test that does not happen to read that field.
 *
 * <p>This walks the compiled package and checks both.
 */
class TimestampCreatorDriftTest {

    /**
     * Records that hold an {@link Instant} and are never built from the wire,
     * with the reason each is not. Jackson is never asked to make one, so a
     * creator on it would be a rite rather than a check.
     *
     * <p>Kept honest by {@link #nothingExemptedIsAWireType()}: a wire record
     * carries {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so listing
     * one here fails rather than silencing the guard that would have caught it.
     */
    private static final Map<String, String> NOT_FROM_THE_WIRE = Map.of(
            "Trade",
            "assembled client-side by MarketTrades from two Orders that were "
                + "themselves parsed; the exchange sends no such object.");

    private static List<Class<?>> _records() throws Exception {
        Path classes = Path.of("target", "classes", "fm");
        assertThat(classes).as("compiled package to scan").exists();

        var found = new ArrayList<Class<?>>();
        try (Stream<Path> files = Files.list(classes)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                Class<?> type = Class.forName("fm." + name.substring(0, name.length() - 6));
                if (type.isRecord()) {
                    found.add(type);
                }
            }
        }
        assertThat(found).as("records in fm").isNotEmpty();
        return found;
    }

    private static Executable _creator(Class<?> type) {
        return Stream.<Executable>concat(
                    Arrays.stream(type.getDeclaredMethods()),
                    Arrays.stream(type.getDeclaredConstructors()))
                .filter(e -> e.isAnnotationPresent(JsonCreator.class))
                .findFirst()
                .orElse(null);
    }

    @Test
    void everyRecordHoldingAnInstantHasACreatorToBuildItFromTheWire() throws Exception {
        var missing = new ArrayList<String>();

        for (Class<?> type : _records()) {
            if (NOT_FROM_THE_WIRE.containsKey(type.getSimpleName())) {
                continue;
            }
            boolean holdsInstant = Arrays.stream(type.getRecordComponents())
                    .anyMatch(c -> c.getType() == Instant.class);
            if (holdsInstant && _creator(type) == null) {
                missing.add(type.getSimpleName());
            }
        }

        assertThat(missing)
                .as("records with an Instant component and no @JsonCreator: "
                    + "their timestamps would deserialize as null")
                .isEmpty();
    }

    @Test
    void everyCreatorTakesExactlyTheComponentsInOrder() throws Exception {
        var drifted = new ArrayList<String>();

        for (Class<?> type : _records()) {
            Executable creator = _creator(type);
            if (creator == null) {
                continue;
            }

            List<String> components = Arrays.stream(type.getRecordComponents())
                    .map(c -> c.getName()).toList();
            List<String> parameters = Arrays.stream(creator.getParameters())
                    .map(p -> {
                        var property = p.getAnnotation(JsonProperty.class);
                        return property == null ? "<unnamed:" + p.getName() + ">" : property.value();
                    })
                    .toList();

            if (!components.equals(parameters)) {
                drifted.add("%s: components %s but creator takes %s"
                        .formatted(type.getSimpleName(), components, parameters));
            }
        }

        assertThat(drifted)
                .as("a creator that does not mirror its record drops or misbinds fields")
                .isEmpty();
    }

    @Test
    void nothingExemptedIsAWireType() throws Exception {
        var wrongly = new ArrayList<String>();

        for (Class<?> type : _records()) {
            if (NOT_FROM_THE_WIRE.containsKey(type.getSimpleName())
                    && type.isAnnotationPresent(JsonIgnoreProperties.class)) {
                wrongly.add(type.getSimpleName());
            }
        }

        assertThat(wrongly)
                .as("exempted from needing a creator, but annotated as something "
                    + "the server sends -- the exemption is hiding the very drift "
                    + "this class exists to catch")
                .isEmpty();
    }
}
