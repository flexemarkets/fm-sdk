package fm.study;

/**
 * A value the manager confirms, or enters, when a rotation is settled: Simple
 * Dividend's dividend for each market, which the manager may only know once
 * the session has run (fm-ui docs/EDITOR-AND-STUDY-REDESIGN.md §7.6).
 * Contract 0.6.
 *
 * <p>A study names these with {@link StudyProvider#atSettle}. The settle page
 * shows each with its default; the host then calls
 * {@link StudyProvider#settle} with the answers and, under each {@code key},
 * the value the manager confirmed. A study reads it there as it reads an
 * answer, and falls back to the answers when it is absent: a host before 0.6,
 * or the study's own command line.
 *
 * @param key          the parameter the confirmed value is passed under; not
 *                     one of the study's own parameter names unless it means
 *                     to replace that answer at settle
 * @param label        what the manager is asked: "STK's dividend"
 * @param help         a sentence on what it decides, or null
 * @param unit         how it is written, as a parameter's hint is:
 *                     "dollars", or null
 * @param defaultValue the value the answers give it, shown filled in, or null
 *                     when the manager must enter one
 */
public record SettleValue(String key, String label, String help, String unit, String defaultValue) {
    /** A value is passed under a name and asked for in words. */
    public SettleValue {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a settle value needs a key");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("settle value '" + key + "' needs a label");
        }
    }
}
