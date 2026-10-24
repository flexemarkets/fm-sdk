package fm.study;

/**
 * What {@link StudyProvider#carry} produces when a period closes: what the
 * next period is staged with, what the study keeps for the periods after,
 * and whether there are any.
 *
 * <p>{@code holdings} and {@code state} are the next rotation's files, as
 * {@link Rotation}'s are: a holdings CSV and a private-state CSV. They take
 * the place of the ones the plan gave that rotation, which a study whose
 * allotments come from the last close can only guess at setup.
 *
 * <p>{@code carried} is the study's own, between periods: the dividends drawn
 * so far, what each person has earned, the allowance shorts are measured
 * against. Nobody else reads it. The host keeps it on the run and hands it
 * back to the next {@code carry} and to {@link StudyProvider#settle}, so a
 * payout that depends on the path can be computed at the end of it.
 *
 * <p>{@code next} says whether the run goes on. Most studies run the periods
 * they planned; one whose end depends on what participants do -- Credit
 * Bubble, liquidated when the claims outrun the pool -- ends sooner, and the
 * periods the plan had left are not run. Contract 0.2.
 *
 * @param holdings the holdings CSV to stage for the next period; null when
 *                 {@code next} is {@link Next#SETTLE}
 * @param state    the private-state CSV to stage, or null when there is none
 * @param carried  the study's state after this period, or null for none
 * @param next     what follows the period this carry stages
 */
public record Carry(String holdings, String state, String carried, Next next) {

    /** What comes after a carry. */
    public enum Next {
        /** Stage the next period; more may follow it, as the plan has them. */
        PERIOD,
        /** Stage the next period, and settle once it has run: it is the last. */
        LAST,
        /** Stage nothing: the run is over, and the period just closed is settled. */
        SETTLE
    }

    /** A period to stage has to be staged with something; a run that is over stages nothing. */
    public Carry {
        next = next == null ? Next.PERIOD : next;
        if (next == Next.SETTLE) {
            if (holdings != null || state != null) {
                throw new IllegalArgumentException("a carry that settles stages nothing");
            }
        } else if (holdings == null || holdings.isBlank()) {
            throw new IllegalArgumentException("a carry needs the next period's holdings");
        }
    }

    /**
     * The next period, with more to follow as the plan has them: contract
     * 0.1's carry.
     *
     * @param holdings the holdings CSV to stage for the next period
     * @param state    the private-state CSV to stage, or null when there is none
     * @param carried  the study's state after this period, or null for none
     */
    public Carry(String holdings, String state, String carried) {
        this(holdings, state, carried, Next.PERIOD);
    }

    /**
     * The next period, and the last: once it has run, it is settled.
     *
     * @param holdings the holdings CSV to stage for it
     * @param state    the private-state CSV to stage, or null when there is none
     * @param carried  the study's state after this period, or null for none
     * @return the carry
     */
    public static Carry last(String holdings, String state, String carried) {
        return new Carry(holdings, state, carried, Next.LAST);
    }

    /**
     * No next period: the run is over, and the period just closed is
     * settled. The host hands {@code settle} the same close this carry read,
     * with {@code carried}; a study that counted something in that close here
     * says so in {@code carried}, and does not count it again there.
     *
     * @param carried the study's state after this period, or null for none
     * @return the carry
     */
    public static Carry settle(String carried) {
        return new Carry(null, null, carried, Next.SETTLE);
    }
}
