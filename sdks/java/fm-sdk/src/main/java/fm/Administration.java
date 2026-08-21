package fm;

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
     */
    Token signup(String accountName, String email, String password);

    Token signup(String accountName, String email, String password,
                 String firstName, String lastName);

    /** Approve an account by name, returning it as it now stands. */
    Account approveAccount(String accountName);

    /**
     * One account by id.
     *
     * <p>Named {@code accountById} rather than overloading {@code account()},
     * which answers a different question — who this connection is signed in as.
     * Two methods a character apart meaning "me" and "whoever you name" is the
     * kind of distinction a reader has to hold in their head, and both Python
     * and TypeScript had already renamed around it.
     */
    Account accountById(long accountId);

    /** One user by id. See {@link #accountById} on the name. */
    Person userById(long userId);

    /** The marketplace's private-trader identifiers. */
    List<String> identifiers(long marketplaceId);

    /** Delete the caller's own account. Destructive, and not undoable. */
    void deleteMyAccount();

    /** Every account on the server. Admin-only. */
    List<Account> accounts();

    /** Delete an account. Destructive, and takes its users with it. */
    void deleteAccount(long accountId);

    /** Create a user in the caller's account. Roles are optional. */
    Person createUser(String email, String password, String firstName,
                      String lastName, String... roles);

    /** Delete a user. Destructive. */
    void deleteUser(long userId);

    /** Create an empty marketplace. See also {@link Management#createMarketplaceFromJson}. */
    Marketplace createMarketplace(String name, String description);

    /** Delete a marketplace, and with it its sessions and their history. */
    void deleteMarketplace(long marketplaceId);

    /**
     * Add a market to a marketplace.
     *
     * <p>Unit bounds are not parameters: they are fixed at 1/100/1, matching
     * fm-lib-net's call. A study that needs other bounds builds its
     * marketplace from JSON, where every field is stated.
     */
    Market createMarket(long marketplaceId, String symbol, String name,
                        long priceMinimum, long priceMaximum, long priceTick,
                        boolean privateMarket);

    /**
     * Mint one-time passcodes for the given users.
     *
     * <p>These are credentials. They are how a classroom signs in without
     * passwords being handed around, and they should be treated like
     * passwords: not logged, not persisted, and delivered to the person they
     * belong to.
     */
    ManagerOtpBundle managerOtpBundle(List<Long> userIds);
}
