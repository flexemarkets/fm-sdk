package fm.study;

import java.util.Map;
import java.util.Set;

/**
 * A robot a study needs in one of its marketplaces. Contract 0.3.
 *
 * <p>Two kinds. Venture's entrepreneurs are the study's own traders: nobody
 * else sells the notes, so a run without them has no supply. CAPM's MVO
 * robots are the participants': the study only fixes the penalty and payoffs
 * they trade with, so a robot and the study's settlement cannot disagree.
 *
 * @param key            the study's name for it, unique in the study:
 *                       {@code good}, {@code bad}, {@code mvo}
 * @param robotId        the robot's manifest id, as the catalogue names it:
 *                       {@code fm-venture-entrepreneur}
 * @param marketplaceKey the plan's marketplace it runs in
 * @param mode           {@link Mode#OWN} or {@link Mode#OFFERED}
 * @param parameters     the robot's parameters by manifest name, as text --
 *                       fixed for the run: a manager cannot change them
 *                       while it lasts
 * @param rotations      the rotations an OWN robot runs in; empty means
 *                       every rotation in its marketplace
 */
public record RobotSpec(String key, String robotId, String marketplaceKey, Mode mode,
        Map<String, String> parameters, Set<Integer> rotations) {

    /** Who runs the robot. */
    public enum Mode {
        /**
         * The study's own trader. The host gives it a person of its own --
         * which setup is handed, to allot -- launches it as that person when
         * each of its rotations' sessions opens, and stops it when it closes.
         * It is never paid.
         */
        OWN,
        /**
         * Offered to the participants, who launch it as they do any robot,
         * with {@link RobotSpec#parameters} fixed by the study.
         */
        OFFERED
    }

    /** The names are required; parameters and rotations may be left empty. */
    public RobotSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a robot needs a key");
        }
        if (robotId == null || robotId.isBlank()) {
            throw new IllegalArgumentException("robot '" + key + "' needs a robot id");
        }
        if (marketplaceKey == null || marketplaceKey.isBlank()) {
            throw new IllegalArgumentException("robot '" + key + "' needs a marketplace");
        }
        if (mode == null) {
            throw new IllegalArgumentException("robot '" + key + "' needs a mode");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        rotations = rotations == null ? Set.of() : Set.copyOf(rotations);
    }

    /**
     * Whether it runs in a rotation.
     *
     * @param rotation a rotation of the plan
     * @return true when the rotation is in its marketplace and among its
     *         rotations, or it names none
     */
    public boolean runsIn(Rotation rotation) {
        return marketplaceKey.equals(rotation.marketplaceKey())
                && (rotations.isEmpty() || rotations.contains(rotation.index()));
    }
}
