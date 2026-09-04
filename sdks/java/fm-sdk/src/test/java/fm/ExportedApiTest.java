package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What module-info promises, held to.
 *
 * <p>Written after fm.role.Streaming was found importing fm.internal.StreamReconnected.
 * Streaming is exported and StreamReconnected was not, so a caller on the module path
 * was handed objects on their own queue whose type they could not name. Nothing
 * caught it: it compiles, and every existing test passed.
 *
 * <p>Two checks, because the leak can take two shapes. A type can escape through
 * a signature, which reflection sees. Or it can escape through a queue declared
 * as {@code BlockingQueue<Object>}, which reflection cannot see and which is how
 * this one escaped.
 */
class ExportedApiTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path CLASSES = Path.of("target/classes");

    @Test
    void noExportedSignatureNamesATypeACallerCannotReach() throws Exception {
        Set<String> exported = _exportedPackages();
        assertThat(exported).isNotEmpty();

        List<String> leaks = new ArrayList<>();
        for (Class<?> exportedType : _publicTypesInExportedPackages(exported)) {
          // The type itself, plus any supertype module-info keeps in. A method
          // declared on a non-exported supertype is still callable through the
          // exported subtype, so its signature is still part of the surface --
          // and it is in no other type's getDeclaredMethods(), so scanning
          // exported types alone cannot see it. fm.role went this way: six
          // interfaces Flexemarkets extends left the scan the day they stopped
          // being exported, silently, with every test still passing.
          for (Class<?> type : _withHiddenSupertypes(exportedType, exported)) {
            for (Method m : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                _check(type, "method " + m.getName(), exported, leaks,
                       _flatten(m.getGenericReturnType()));
                for (Type p : m.getGenericParameterTypes()) {
                    _check(type, "method " + m.getName(), exported, leaks, _flatten(p));
                }
            }
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                if (!Modifier.isPublic(c.getModifiers())) continue;
                for (Type p : c.getGenericParameterTypes()) {
                    _check(type, "constructor", exported, leaks, _flatten(p));
                }
            }
            for (Field f : type.getDeclaredFields()) {
                if (!Modifier.isPublic(f.getModifiers())) continue;
                _check(type, "field " + f.getName(), exported, leaks, _flatten(f.getGenericType()));
            }
          }
        }
        assertThat(leaks)
            .as("an exported type may not name one module-info keeps in")
            .isEmpty();
    }

    /**
     * listen() and subscribe() hand the caller a queue and put objects on it.
     * Those objects are the contract, whatever the queue's element type says,
     * so every one of them has to be nameable. This is the check that would
     * have caught StreamReconnected.
     */
    @Test
    void everyEventPutOnTheCallerQueueIsExported() throws IOException {
        Set<String> exported = _exportedPackages();
        String body = Files.readString(MAIN.resolve("fm/internal/Events.java"));

        Matcher offered = Pattern.compile("_queue\\.offer\\(\\s*new\\s+(\\w+)").matcher(body);
        List<String> unreachable = new ArrayList<>();
        while (offered.find()) {
            String simple = offered.group(1);
            String pkg = _packageDeclaring(simple);
            if (pkg != null && !exported.contains(pkg)) {
                unreachable.add(simple + " reaches the caller's queue from " + pkg);
            }
        }
        assertThat(unreachable)
            .as("a caller cannot pattern match on a type it cannot name")
            .isEmpty();
    }

    private static void _check(Class<?> owner, String where, Set<String> exported,
                               List<String> leaks, Set<Class<?>> referenced) {
        for (Class<?> c : referenced) {
            while (c.isArray()) c = c.getComponentType();
            if (c.isPrimitive() || c.getPackage() == null) continue;
            String pkg = c.getPackage().getName();
            if (!pkg.equals("fm") && !pkg.startsWith("fm.")) continue;
            if (!exported.contains(pkg)) {
                leaks.add(owner.getName() + " exposes " + c.getName() + " through its " + where);
            }
        }
    }

    /** A type and every type argument inside it: Consumer&lt;GapEvent&gt; yields both. */
    private static Set<Class<?>> _flatten(Type t) {
        Set<Class<?>> out = new LinkedHashSet<>();
        if (t instanceof Class<?> c) {
            out.add(c);
        } else if (t instanceof ParameterizedType p) {
            out.addAll(_flatten(p.getRawType()));
            for (Type arg : p.getActualTypeArguments()) out.addAll(_flatten(arg));
        } else if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) out.addAll(_flatten(b));
            for (Type b : w.getLowerBounds()) out.addAll(_flatten(b));
        }
        return out;
    }

    private static Set<String> _exportedPackages() throws IOException {
        Matcher m = Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
                           .matcher(Files.readString(MAIN.resolve("module-info.java")));
        List<String> found = new ArrayList<>();
        while (m.find()) found.add(m.group(1));
        return Set.copyOf(found);
    }

    /**
     * {@code type}, plus every supertype of it that module-info does not
     * export.
     *
     * <p>An exported supertype needs no visit: it is in the scan already, on
     * its own account. A non-exported one is visited here or nowhere, and its
     * methods are reachable through this subtype regardless.
     */
    private static List<Class<?>> _withHiddenSupertypes(Class<?> type, Set<String> exported) {
        List<Class<?>> found = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();

        found.add(type);
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> current = pending.poll();
            List<Class<?>> parents = new ArrayList<>(List.of(current.getInterfaces()));
            if (null != current.getSuperclass()) parents.add(current.getSuperclass());
            for (Class<?> parent : parents) {
                if (!seen.add(parent)) continue;
                // java.* and anything exported is either not ours or already scanned.
                String pkg = null == parent.getPackage() ? "" : parent.getPackage().getName();
                if (pkg.startsWith("java.") || exported.contains(pkg)) continue;
                found.add(parent);
                pending.add(parent);
            }
        }
        return found;
    }

    private static List<Class<?>> _publicTypesInExportedPackages(Set<String> exported) throws Exception {
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CLASSES)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = CLASSES.relativize(p).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                if (name.equals("module-info")) continue;
                Class<?> c = Class.forName(name, false, ExportedApiTest.class.getClassLoader());
                if (!Modifier.isPublic(c.getModifiers())) continue;
                if (c.getPackage() != null && exported.contains(c.getPackage().getName())) types.add(c);
            }
        }
        return types;
    }

    private static String _packageDeclaring(String simpleName) throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            Path home = walk.filter(p -> p.getFileName().toString().equals(simpleName + ".java"))
                            .findFirst().orElse(null);
            if (home == null) return null;
            Matcher m = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
                               .matcher(Files.readString(home));
            return m.find() ? m.group(1) : null;
        }
    }
}
