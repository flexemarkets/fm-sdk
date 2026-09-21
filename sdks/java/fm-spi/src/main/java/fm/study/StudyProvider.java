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

    default String studySpiVersion() {
        return STUDY_SPI_VERSION;
    }
}
