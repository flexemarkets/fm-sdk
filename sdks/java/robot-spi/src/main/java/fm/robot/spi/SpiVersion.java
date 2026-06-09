package fm.robot.spi;

/**
 * The SPI contract version.
 *
 * <p>Semantics: bump the <em>minor</em> for additive, backward-compatible
 * changes (a new default method, a new value type); bump the <em>major</em>
 * for anything a host or plugin must adapt to. A host accepts any plugin whose
 * major matches its own.
 */
public final class SpiVersion {

    /** The version this artifact publishes. Keep in sync with the pom version's major.minor. */
    public static final String CURRENT = "1.0";

    private SpiVersion() {
    }
}
