package fm.study;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a host's pages need to know about a study beyond its plan: who owns
 * each setting the study writes, and how a run of it is shown and joined
 * (fm-ui docs/EDITOR-AND-STUDY-REDESIGN.md §7.1–7.3). Answered by
 * {@link StudyProvider#facts}, for the answers a run was set up with. Contract
 * 0.6.
 *
 * <p>{@code owned} names each setting the study writes that the manager should
 * not treat as theirs, by a key from {@link #config}, {@link #market} or
 * {@link #robot}: {@link Owner#LOCKED}, the study needs it as it is, and the
 * editor shows it read-only, "Set by" the study; {@link Owner#STUDY}, the
 * study's value, safe to change, shown as the study's. A setting not named is
 * the manager's.
 *
 * @param owned        the study's settings and who owns each, by key
 * @param marketsFixed whether the set of markets is the study's: none added,
 *                     removed or renamed
 * @param robotsOpen   whether a manager may add robots of their own beside the
 *                     study's
 * @param hidesTotal   whether a participant must never see how many periods
 *                     there are ("of N"): Lucas, whose end is the point
 * @param lateJoiners  who may join a run once it has started
 * @param managerHolds whether the manager is allotted (Credit Bubble's pool),
 *                     and so may not also take part
 * @param showsRank    whether a participant's result card ranks them, by
 *                     default; the manager may turn it off for a run
 */
public record StudyFacts(Map<String, Owner> owned, boolean marketsFixed, boolean robotsOpen,
                         boolean hidesTotal, LateJoiners lateJoiners, boolean managerHolds, boolean showsRank) {

    /** Who owns a setting the study writes. */
    public enum Owner {
        /** The study needs it as it is: read-only in the editor. */
        LOCKED,
        /** The study's value, which a manager may change, mid-run included. */
        STUDY
    }

    /** Who may join a run once its first session has opened. */
    public enum LateJoiners {
        /** Nobody: every periodic study, and Smith 62, whose groups rotate. */
        NONE,
        /** From the next rotation, which is staged with them. */
        NEXT_ROTATION
    }

    /**
     * What a study answers that says nothing: every setting the manager's,
     * markets and robots open, the total shown, people joining from the next
     * rotation, the manager not allotted, and results ranked.
     */
    public static final StudyFacts NONE =
            new StudyFacts(Map.of(), false, true, false, LateJoiners.NEXT_ROTATION, false, true);

    public StudyFacts {
        owned = owned == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(owned));
        if (lateJoiners == null) {
            throw new IllegalArgumentException("a study says who may join late");
        }
        for (String key : owned.keySet()) {
            if (!key.startsWith("config:") && !key.startsWith("market:") && !key.startsWith("robot:")) {
                throw new IllegalArgumentException("'" + key + "' names no setting: use config(), market() or robot()");
            }
        }
    }

    /** A marketplace configuration key the study writes, such as its session length. */
    public static String config(String key) {
        return "config:" + key;
    }

    /** One field of one of the study's markets, by its symbol; {@code *} for every field. */
    public static String market(String symbol, String field) {
        return "market:" + symbol + ":" + field;
    }

    /** One parameter of one of the study's robots, by the robot's id. */
    public static String robot(String robotId, String parameter) {
        return "robot:" + robotId + ":" + parameter;
    }

    /** Who owns a setting, or null when it is the manager's. */
    public Owner owner(String key) {
        return owned.get(key);
    }
}
