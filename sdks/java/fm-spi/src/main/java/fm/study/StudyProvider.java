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
 *
 * <p>A multi-period study whose next allotment comes from the last close --
 * a dividend drawn and paid, shorts carried -- also overrides {@link #carry},
 * and its {@link #settle} reads what the periods carried. Contract 0.1.
 */
public interface StudyProvider {
    /** The contract's major.minor; a host rejects a provider whose major differs from its own. */
    String STUDY_SPI_VERSION = "0.1";

    /**
     * The study's id, as its page and its command line name it.
     *
     * @return the id, {@code smith62} and the like
     */
    String id();

    /**
     * A short name, for a list.
     *
     * @return the name a manager sees beside the others
     */
    String name();

    /**
     * A sentence or two, for the setup page.
     *
     * @return what the study is, in the manager's terms
     */
    String description();

    /**
     * What the manager is asked, with defaults; the host renders a form
     * from these.
     *
     * @return the parameters, in the order the form should show them
     */
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
     * @return each participant's payoff, and the report to show them
     * @throws UnsupportedOperationException when the study has no settlement
     * @throws IllegalArgumentException      for a holding the study cannot
     *                                       settle, with the reason
     */
    default Settlement settle(Map<String, String> parameters, Rotation rotation, List<ParticipantHolding> holdings) {
        throw new UnsupportedOperationException(id() + " has no settlement of its own");
    }

    /**
     * Settle one rotation, knowing what the periods before it carried.
     *
     * <p>What a host calls. A study whose payout depends on the path -- the
     * dividends each period paid on what was held through it -- overrides
     * this one; every other study leaves it, and it settles as
     * {@link #settle(Map, Rotation, List)} does.
     *
     * @param parameters the answers the run was set up with
     * @param rotation   the rotation that just ran
     * @param holdings   every participant's holding at close
     * @param carried    what the last {@link #carry} in this rotation's
     *                   marketplace kept, or null when none ran
     * @return each participant's payoff, and the report to show them
     * @throws UnsupportedOperationException when the study has no settlement
     * @throws IllegalArgumentException      for a holding the study cannot
     *                                       settle, with the reason
     */
    default Settlement settle(Map<String, String> parameters, Rotation rotation, List<ParticipantHolding> holdings,
            String carried) {
        return settle(parameters, rotation, holdings);
    }

    /**
     * Carry one period into the next: from what each participant held when
     * the rotation's session closed, the allotment the next one opens with.
     *
     * <p>The step a multi-period study takes between sessions -- draw the
     * period's dividend, pay it, carry the short allowance -- which cannot be
     * planned at setup because it starts from the close. The host calls it
     * once per closed period that has a next one in the same marketplace,
     * stages the result in place of what the plan gave that rotation, and
     * keeps {@link Carry#carried} for the next call and for {@code settle}.
     * A study whose rotations are all known at setup leaves the default, and
     * runs its plan as written.
     *
     * @param parameters the answers the run was set up with
     * @param rotation   the rotation that just ran
     * @param holdings   every participant's holding at close
     * @param carried    what the last carry in this marketplace kept, or
     *                   null before the first
     * @return the next period's holdings and state, and what to keep
     * @throws UnsupportedOperationException when the study does not carry
     * @throws IllegalArgumentException      for a holding the study cannot
     *                                       carry, with the reason
     */
    default Carry carry(Map<String, String> parameters, Rotation rotation, List<ParticipantHolding> holdings,
            String carried) {
        throw new UnsupportedOperationException(id() + " plans every period at setup and carries nothing");
    }

    /**
     * The contract this provider was built against.
     *
     * @return {@link #STUDY_SPI_VERSION}, unless a provider has reason to
     *         say otherwise
     */
    default String studySpiVersion() {
        return STUDY_SPI_VERSION;
    }
}
