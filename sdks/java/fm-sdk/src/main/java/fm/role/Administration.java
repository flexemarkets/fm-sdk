package fm.role;

import fm.model.Account;
import fm.model.Holding;
import fm.model.ManagerOtpBundle;
import fm.model.Market;
import fm.model.Person;
import fm.model.TickGrid;
import fm.model.Token;
import fm.error.ConflictException;
import java.util.List;


/**
 * Creating accounts and users, approving them, deleting them, and minting
 * one-time passcodes.
 *
 * <p>fm-server's administrative surface. It is in the SDK because fm-robots'
 * manager — the tool that runs a course — is built on it and had no way off
 * fm-lib-net otherwise.
 *
 * <p>Several of these are destructive and one issues credentials. Authorization
 * is the server's: they need an admin or a manager and it answers 401/403
 * otherwise. Holding this type is not holding the right, only the ability to
 * ask — which is exactly why the type is worth having separately. A signature
 * that takes {@link Reading} cannot delete an account; one that takes
 * {@code Administration} says plainly that it might.
 *
 * <p>The reads here stay here rather than joining {@link Reading}: reading every
 * account on the server is an administrative act, and a role is what an
 * implementation can do, not what shape its return value has.
 */
public interface Administration {

    /**
     * Register a new account and its owner, returning the owner's token.
     *
     * <p>Account names are unique. A clash raises {@link
     * fm.ConflictException}, whose {@code failure().suggestedName()}
     * carries the server's proposed alternative -- worth surfacing rather than
     * retrying blindly, since the suggestion is what the user will be known as.
     *
     * @param accountName the account to create; must not already exist
     * @param email       the owner's email, which is also their sign-in name
     * @param password    the owner's password
     * @return a token for the new account's owner, already signed in
     */
    Token signup(String accountName, String email, String password);

    /**
     * Sign up, naming the owner.
     *
     * @param accountName the account to create; must not already exist
     * @param email       the owner's email, which is also their sign-in name
     * @param password    the owner's password
     * @param firstName   the owner's given name
     * @param lastName    the owner's family name
     * @return a token for the new account's owner, already signed in
     */
    Token signup(String accountName, String email, String password,
                 String firstName, String lastName);

    /**
     * Approve an account by name, returning it as it now stands.
     *
     * @param accountName the account to approve
     * @return the account, with {@link Account#isApproved()} now true
     */
    Account approveAccount(String accountName);

    /**
     * One account by id.
     *
     * <p>Named {@code accountById} rather than overloading {@code account()},
     * which answers a different question — who this connection is signed in as.
     * Two methods a character apart meaning "me" and "whoever you name" is the
     * kind of distinction a reader has to hold in their head, and both Python
     * and TypeScript had already renamed around it.
     *
     * @param accountId the account's id
     * @return the account
     */
    Account accountById(long accountId);

    /**
     * One user by id. See {@link #accountById} on the name.
     *
     * @param userId the user's id
     * @return the user
     */
    Person userById(long userId);

    /**
     * The marketplace's private-trader identifiers.
     *
     * @param marketplaceId the marketplace
     * @return the identifiers, empty when the marketplace has no private markets
     */
    List<String> identifiers(long marketplaceId);

    /** Delete the caller's own account. Destructive, and not undoable. */
    void deleteMyAccount();

    /**
     * Every account on the server. Admin-only.
     *
     * @return every account
     */
    List<Account> accounts();

    /**
     * Delete an account. Destructive, and takes its users with it.
     *
     * @param accountId the account to delete
     */
    void deleteAccount(long accountId);

    /**
     * Create a user in the caller's account. Roles are optional.
     *
     * @param email     the user's email, which is also their sign-in name
     * @param password  the user's initial password
     * @param firstName the user's given name
     * @param lastName  the user's family name
     * @param roles     the roles to grant; none for an ordinary participant
     * @return the user as created, with its server-assigned id
     */
    Person createUser(String email, String password, String firstName,
                      String lastName, String... roles);

    /**
     * Delete a user. Destructive.
     *
     * @param userId the user to delete
     */
    void deleteUser(long userId);

    /**
     * Delete a marketplace, and with it its sessions and their history.
     *
     * <p>There is no {@code createMarketplace(name, description)} to pair with
     * this. It existed and could not succeed: the server requires at least one
     * market -- {@code MARKETPLACE_INVALID: At least one market is required} --
     * and that method sent only a name and a description, so every call was a
     * 400. Use {@link Management#createMarketplaceFromJson}, which is what the
     * studies have always used.
     *
     * @param marketplaceId the marketplace to delete
     */
    void deleteMarketplace(long marketplaceId);

    /**
     * Add a market to a marketplace.
     *
     * <p>Both dimensions are the caller's. Unit bounds used to be fixed at
     * 1/100/1 with no way to say otherwise, and the javadoc sent anyone
     * needing other sizes off to build the whole marketplace from JSON — for a
     * market whose price grid this same call would happily set. The server
     * enforces the two identically, so the API offers them identically;
     * {@link TickGrid#units()} is the old default for callers who wanted it.
     *
     * @param marketplaceId the marketplace to add the market to
     * @param symbol        the market's ticker symbol, unique within the marketplace
     * @param name          the market's display name
     * @param price         the legal prices: bounds and tick
     * @param units         the legal order sizes; {@link TickGrid#units()} for the usual 1/100/1
     * @param privateMarket whether the market is restricted to named traders
     * @return the market as created, with its server-assigned id
     */
    Market createMarket(long marketplaceId, String symbol, String name,
                        TickGrid price, TickGrid units, boolean privateMarket);

    /**
     * Mint one-time passcodes for the given users.
     *
     * <p>These are credentials. They are how a classroom signs in without
     * passwords being handed around, and they should be treated like
     * passwords: not logged, not persisted, and delivered to the person they
     * belong to.
     *
     * @param userIds the users to mint passcodes for
     * @return the passcodes and the instant they expire
     */
    ManagerOtpBundle managerOtpBundle(List<Long> userIds);
}
