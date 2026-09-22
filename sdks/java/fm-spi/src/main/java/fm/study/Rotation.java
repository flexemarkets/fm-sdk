package fm.study;

/**
 * One trading session of the study, on one of its marketplaces: what to
 * stage before it opens.
 *
 * <p>{@code holdings} and {@code state} are the files the study's command
 * line writes, as text -- a holdings CSV and a private-state CSV -- so the
 * host imports them through the same doors a manager's upload uses, and a
 * plan the host stages and a plan the command line writes to disk are the
 * same bytes.
 *
 * @param index          1-based, in the order the study runs them
 * @param label          what the study calls it: {@code BHSHBLSL}, {@code Period 3}
 * @param marketplaceKey the {@link MarketplaceSpec#key} it runs in
 * @param holdings       the holdings CSV to stage
 * @param state          the private-state CSV to stage, or null when the
 *                       study has none
 */
public record Rotation(int index, String label, String marketplaceKey, String holdings, String state) {
    /** A rotation runs somewhere, in some order, with something to stage. */
    public Rotation {
        if (index < 1) {
            throw new IllegalArgumentException("a rotation's index starts at 1");
        }
        if (marketplaceKey == null || marketplaceKey.isBlank()) {
            throw new IllegalArgumentException("rotation " + index + " needs a marketplace key");
        }
        if (holdings == null || holdings.isBlank()) {
            throw new IllegalArgumentException("rotation " + index + " needs holdings");
        }
    }
}
