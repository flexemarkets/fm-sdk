package fm.study;

/**
 * What {@link StudyProvider#carry} produces when a period closes: what the
 * next period is staged with, and what the study keeps for the periods after.
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
 * @param holdings the holdings CSV to stage for the next period
 * @param state    the private-state CSV to stage, or null when there is none
 * @param carried  the study's state after this period, or null for none
 */
public record Carry(String holdings, String state, String carried) {
    /** The next period has to be staged with something. */
    public Carry {
        if (holdings == null || holdings.isBlank()) {
            throw new IllegalArgumentException("a carry needs the next period's holdings");
        }
    }
}
