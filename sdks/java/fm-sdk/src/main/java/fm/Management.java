package fm;

import java.nio.file.Path;
import java.util.List;


/**
 * Running an experiment, as opposed to trading in one: stage the opening
 * positions, open the session, close it.
 *
 * <p>Every study in fm-robots drives this sequence. It arrived late enough that
 * all of it was defaulted, which is how a study could be handed a connection
 * that compiled and then refused at the moment it mattered — mid-run, after
 * participants were already sitting there.
 *
 * <p>All abstract now. Reading it back is {@link Reading}'s job:
 * {@code allotments}, {@code downloadHoldings} and the rest are reads and sit
 * there, so this role is only the things that change something.
 *
 * <p>Authorization stays the server's business. These need a manager or an
 * admin and it answers 401/403 otherwise — holding the type is not holding the
 * right, only the ability to ask.
 */
public interface Management {

    /** Opens the marketplace's session, returning it in its new state. */
    Session openSession(long marketplaceId);

    Session pauseSession(long marketplaceId);

    Session closeSession(long marketplaceId);

    /**
     * Create a marketplace from its JSON definition, returning what was made.
     *
     * <p>Takes JSON rather than a builder because that is how the definitions
     * exist: a study keeps its marketplace as a file it can print, diff and
     * hand to someone, and the CLI's {@code --dry-run} prints exactly the
     * document that would be posted. A typed builder here would mean the thing
     * printed and the thing sent were assembled by different code.
     *
     * <p>The JSON is parsed before it is sent, so a malformed definition fails
     * locally rather than as a 400 from the server.
     */
    Marketplace createMarketplaceFromJson(String json);

    /**
     * Stage the opening positions for the next session, returning them as read
     * back from the server.
     *
     * <p><b>Staged, not applied.</b> An allocation lands when a <em>closed</em>
     * session is opened; pausing and re-opening does not consume it. So the
     * sequence is allocate, then {@link #closeSession} if one is running, then
     * {@link #openSession}. Calling this against a live session appears to
     * succeed and changes nobody's position.
     *
     * <p>Takes {@link Holding}s because that is what a caller has -- the shape
     * it reads positions in and computes with. The allotment form the endpoint
     * wants is an encoding detail and is applied here.
     */
    List<Holding> allocate(long marketplaceId, List<Holding> holdings);

    /**
     * Load opening positions from a holdings CSV, returning what was created.
     *
     * <p>Stages the next allocation on the same terms as {@link #allocate}: it
     * lands when a closed session is opened.
     *
     * <p>A {@link Path}: the file is the thing being uploaded. fm-lib-net's
     * equivalent takes a Spring {@code Resource}, which is exactly the sort of
     * dependency in a signature that this SDK exists to avoid.
     */
    List<Holding> uploadHoldings(long marketplaceId, Path csv);
}
