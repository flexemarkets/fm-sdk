package fm;


/**
 * Who a connection is, and where it points.
 *
 * <p>The one role every implementation fills. A connection that cannot say who
 * it is is not a connection — which is why nothing here is optional, and why
 * {@link Flexemarkets} is the only interface in this package that does not need
 * to declare it separately.
 */
public interface Identity {

    /**
     * The account this connection is signed in to.
     *
     * <p>Not to be confused with {@link Administration#accountById}, which
     * answers about whoever you name rather than about the caller.
     *
     * @return the caller's own account
     */
    Account account();

    /**
     * @return the id of the account this connection is signed in to
     */
    long accountId();

    /**
     * @return the name of the account this connection is signed in to
     */
    String accountName();

    /**
     * The person this connection is signed in as.
     *
     * @return the caller's own user, carrying the roles the server granted
     */
    Person user();

    /**
     * @return the id of the person this connection is signed in as
     */
    long userId();

    /**
     * The token this connection signed in with.
     *
     * <p>Exposed so a caller can mint a sibling connection on the same
     * identity without holding the password again -- {@code connect(token
     * .token(), ...)} takes it directly.
     *
     * @return the token this connection holds
     */
    Token token();

    /**
     * Whether this connection's user holds {@code role}, spelled as the server
     * spells it — {@code "ROLE_ADMIN"}, {@code "ROLE_MANAGER"}, {@code
     * "ROLE_USER"}.
     *
     * <p>The general form of {@link #isAdmin} and {@link #isManager}, which are
     * named because they are the two a caller asks about. Anything the server
     * grows later is reachable through this without another method.
     *
     * @param role the role to test for, in the server's spelling
     * @return true if this connection's user holds it
     */
    default boolean hasRole(String role) {
        var person = user();
        if (null == person || null == person.roles()) {
            return false;
        }
        for (var held : person.roles()) {
            if (null != role && role.equals(held)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this connection's user holds ROLE_ADMIN.
     *
     * @return true if the user is an administrator
     */
    default boolean isAdmin() {
        return hasRole("ROLE_ADMIN");
    }

    /**
     * Whether this connection's user holds ROLE_MANAGER.
     *
     * <p>The role that runs a study — opening and closing sessions, staging
     * allocations, minting passcodes.
     *
     * @return true if the user is a manager
     */
    default boolean isManager() {
        return hasRole("ROLE_MANAGER");
    }

    /**
     * The marketplace this connection was pointed at, from its endpoint.
     *
     * @return the endpoint's marketplace id
     */
    long endpointMarketplaceId();

    /**
     * @return the base URL this connection talks to
     */
    String endpointUrl();
}
