package fm.study;

import java.util.List;

/**
 * What one participant held at the end of a session, as the platform
 * exported it: cash and units, each beside what they started with.
 *
 * @param id          the person's id on the platform
 * @param email       their email, which the study's files key on
 * @param name        their allotment name for the session -- {@code B01},
 *                    {@code S03} -- which is how they recognise their row
 * @param cashInitial cents at open
 * @param cash        cents at close
 * @param securities  each market's units, at open and at close
 */
public record ParticipantHolding(long id, String email, String name, long cashInitial, long cash, List<Security> securities) {
    /** A participant who held no security is one with an empty list, not a null. */
    public ParticipantHolding {
        securities = securities == null ? List.of() : List.copyOf(securities);
    }

    /**
     * One market's position.
     *
     * @param symbol       the market's ticker
     * @param unitsInitial units at open
     * @param units        units at close
     */
    public record Security(String symbol, long unitsInitial, long units) {
    }
}
