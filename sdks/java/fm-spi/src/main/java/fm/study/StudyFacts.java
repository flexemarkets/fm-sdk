package fm.study;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Owned keys name a setting, and a study says who may join late. */
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

    /**
     * A marketplace configuration key the study writes, such as its session length.
     *
     * @param key the configuration key, {@code fm.marketplace.session.duration}
     * @return its key in {@link #owned}
     */
    public static String config(String key) {
        return "config:" + key;
    }

    /**
     * One field of one of the study's markets, by its symbol.
     *
     * @param symbol the market's symbol
     * @param field  the field, {@code priceTick}; {@code *} for every field
     * @return its key in {@link #owned}
     */
    public static String market(String symbol, String field) {
        return "market:" + symbol + ":" + field;
    }

    /**
     * One parameter of one of the study's robots, by the robot's id.
     *
     * @param robotId   the robot's id, {@code local:fm-maker}
     * @param parameter the parameter's name
     * @return its key in {@link #owned}
     */
    public static String robot(String robotId, String parameter) {
        return "robot:" + robotId + ":" + parameter;
    }

    /**
     * Who owns a setting.
     *
     * @param key a key from {@link #config}, {@link #market} or {@link #robot}
     * @return its owner, or null when it is the manager's
     */
    public Owner owner(String key) {
        return owned.get(key);
    }

    /**
     * Facts built from what the study writes: each configuration key of its
     * marketplaces {@link Owner#LOCKED} unless named in {@link Builder#study},
     * each market's fields the study's ({@link Owner#STUDY}) and the set of
     * markets fixed, and each parameter its robots fix {@link Owner#LOCKED}.
     *
     * @return a builder that starts from {@link #NONE}
     */
    public static Builder builder() {
        return new Builder();
    }

    /** See {@link #builder()}. Starts from {@link #NONE}. */
    public static final class Builder {
        private final Map<String, Owner> owned = new LinkedHashMap<>();
        private final Set<String> studyKeys = new HashSet<>();
        private boolean marketsFixed;
        private boolean robotsOpen = true;
        private boolean hidesTotal;
        private LateJoiners lateJoiners = LateJoiners.NEXT_ROTATION;
        private boolean managerHolds;
        private boolean showsRank = true;

        private Builder() {
        }

        /**
         * The study's marketplaces: their configuration and markets, as {@link #builder()} says.
         *
         * @param marketplaces the marketplaces the study writes
         * @return this builder
         */
        public Builder marketplaces(List<MarketplaceSpec> marketplaces) {
            for (MarketplaceSpec marketplace : marketplaces) {
                for (String key : marketplace.configuration().keySet()) {
                    owned.putIfAbsent(config(key), Owner.LOCKED);
                }
                for (MarketSpec market : marketplace.markets()) {
                    owned.put(market(market.symbol(), "*"), Owner.STUDY);
                }
                marketsFixed = true;
            }
            return this;
        }

        /**
         * The study's robots: each parameter one fixes is locked.
         *
         * @param robots the robots the study writes
         * @return this builder
         */
        public Builder robots(List<RobotSpec> robots) {
            for (RobotSpec robot : robots) {
                for (String parameter : robot.parameters().keySet()) {
                    owned.put(robot(robot.robotId(), parameter), Owner.LOCKED);
                }
            }
            return this;
        }

        /**
         * Configuration keys that are the study's but safe for a manager to change, such as the session length.
         *
         * @param configurationKeys the keys, without the {@code config:} prefix
         * @return this builder
         */
        public Builder study(String... configurationKeys) {
            for (String key : configurationKeys) {
                studyKeys.add(key);
                owned.put(config(key), Owner.STUDY);
            }
            return this;
        }

        /**
         * Whether a manager may add robots of their own beside the study's.
         *
         * @param open true by default
         * @return this builder
         */
        public Builder robotsOpen(boolean open) {
            this.robotsOpen = open;
            return this;
        }

        /**
         * Participants never see how many periods there are.
         *
         * @return this builder
         */
        public Builder hidesTotal() {
            this.hidesTotal = true;
            return this;
        }

        /**
         * Who may join a run once it has started.
         *
         * @param who {@link LateJoiners#NEXT_ROTATION} by default
         * @return this builder
         */
        public Builder lateJoiners(LateJoiners who) {
            this.lateJoiners = who;
            return this;
        }

        /**
         * The manager is allotted, and so may not also take part.
         *
         * @return this builder
         */
        public Builder managerHolds() {
            this.managerHolds = true;
            return this;
        }

        /**
         * Whether a participant's result card ranks them by default.
         *
         * @param ranks true by default
         * @return this builder
         */
        public Builder showsRank(boolean ranks) {
            this.showsRank = ranks;
            return this;
        }

        /**
         * The facts, with every key named in {@link #study} the study's.
         *
         * @return the facts
         */
        public StudyFacts build() {
            for (String key : studyKeys) {
                owned.put(config(key), Owner.STUDY);
            }
            return new StudyFacts(owned, marketsFixed, robotsOpen, hidesTotal, lateJoiners, managerHolds, showsRank);
        }
    }
}
