package fm.study;

import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * A run's chance, drawn once and the same every time it is asked: Lucas's
 * high or low and its stopping period, a prediction market's outcomes, SSW's
 * dividends (fm-ui docs/EDITOR-AND-STUDY-REDESIGN.md §7.5). Contract 0.6.
 *
 * <p>A host keeps a seed for each run, made when the run is set up, and puts
 * it in every call's parameters under {@link #SEED}. A study draws from
 * {@link #of}, naming what the draw is for and which rotation it belongs to:
 * the same seed, purpose and index give the same numbers, so a settle that is
 * previewed and then committed pays what the preview showed, and a plan made
 * again for the same people draws what it drew before.
 *
 * <p>Parameters without a seed -- a study's own command line, or a host before
 * 0.6 -- draw afresh, as every study did before.
 */
public final class Draws {

    /** The parameter a host puts a run's seed under: a whole number, as text. */
    public static final String SEED = "fm.seed";

    private Draws() {
    }

    /**
     * The numbers for one draw of a run.
     *
     * @param parameters the parameters the host passed, with the run's seed
     *                   under {@link #SEED}, or without one to draw afresh
     * @param purpose    what the draw decides, "dividend" or "outcome": two
     *                   draws for one rotation do not share their numbers
     * @param index      the rotation it is for, or 0 for one made at setup
     * @return a generator, the same sequence for the same seed, purpose and index
     * @throws IllegalArgumentException when the seed is not a whole number
     */
    public static RandomGenerator of(Map<String, String> parameters, String purpose, int index) {
        String seed = parameters == null ? null : parameters.get(SEED);
        if (seed == null || seed.isBlank()) {
            return new SplittableRandom();
        }
        long value;
        try {
            value = Long.parseLong(seed.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SEED + " must be a whole number, not '" + seed + "'", e);
        }
        long mixed = value;
        mixed = mixed * 31 + (purpose == null ? 0 : purpose.hashCode());
        mixed = mixed * 31 + index;
        return new SplittableRandom(mixed);
    }
}
