package fm.study;

/**
 * What a participant is paid at the end of a rotation: the cash they take
 * into the settlement session. Positions are zeroed and trading is locked
 * there, so every study's payoff has one shape.
 *
 * @param id    the person's id on the platform
 * @param email their email
 * @param name  the allotment name to carry into the settlement session,
 *              so they recognise their row
 * @param cash  the payoff, in cents; may be negative
 */
public record Payoff(long id, String email, String name, long cash) {
}
