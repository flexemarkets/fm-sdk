package fm.study;

import java.util.List;
import java.util.Map;

import fm.manifest.ParameterSpec;

/**
 * A study the platform can set up for a manager: what it asks, and what it
 * produces when asked.
 *
 * <p>The contract between a study's own logic -- the group assignment, the
 * rotations, the induced values, the marketplaces it needs -- and a host
 * that runs that logic on a manager's behalf, from a form rather than a
 * command line. The study keeps the arithmetic; the host keeps the
 * accounts, the marketplaces and the sessions. Found through
 * {@code ServiceLoader}, so an implementation must have a no-argument
 * constructor and be registered under {@code META-INF/services}.
 *
 * <p>A study's command line and the host produce the same plan from the
 * same parameters, because both call {@link #setup}. That is the point:
 * one implementation of the study, however it is driven.
 */
public interface StudyProvider {
    /** The contract's major.minor; a host rejects a provider whose major differs from its own. */
    String STUDY_SPI_VERSION = "0.0";

    /** The study's id, as its page and its command line name it: {@code smith62}. */
    String id();

    /** A short name, for a list. */
    String name();

    /** A sentence or two, for the setup page. */
    String description();

    /** What the manager is asked, with defaults; the host renders a form from these. */
    List<ParameterSpec> parameters();

    /**
     * Plan a run of the study for these participants.
     *
     * @param parameters   the manager's answers by parameter name, as text;
     *                     a missing name means the default
     * @param participants the people taking part, in the order the manager
     *                     gave them
     * @return the marketplaces to create and the rotations to run in them
     * @throws IllegalArgumentException for a parameter the study refuses,
     *                                  with the reason in the message
     */
    StudyPlan setup(Map<String, String> parameters, List<Participant> participants);

    /**
     * Settle one rotation: what each participant is paid, from what they
     * held when its session closed and the private state the rotation was
     * staged with.
     *
     * <p>The back half of a study's {@code start}: the platform stages the
     * payoffs as the next allotment -- cash only, positions zeroed, trading
     * locked -- and opens a settlement session for participants to see
     * them. A study with nothing to settle leaves the default, which says
     * so.
     *
     * @param parameters the answers the run was set up with
     * @param rotation   the rotation that just ran, with the state it was
     *                   staged with -- the study's own file, to read back
     * @param holdings   every participant's holding at close
     * @throws UnsupportedOperationException when the study has no settlement
     * @throws IllegalArgumentException      for a holding the study cannot
     *                                       settle, with the reason
     */
    default Settlement settle(Map<String, String> parameters, Rotation rotation, List<ParticipantHolding> holdings) {
        throw new UnsupportedOperationException(id() + " has no settlement of its own");
    }

    default String studySpiVersion() {
        return STUDY_SPI_VERSION;
    }
}
