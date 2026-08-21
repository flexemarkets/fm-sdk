package fm;

import fm.Types.Account;
import fm.Types.Person;
import fm.Types.Token;

/**
 * Who a connection is, and where it points.
 *
 * <p>The one role every implementation fills. A connection that cannot say who
 * it is is not a connection — which is why nothing here is optional, and why
 * {@link Flexemarkets} is the only interface in this package that does not need
 * to declare it separately.
 */
public interface Identity {

    Account account();

    long accountId();

    String accountName();

    Person user();

    long userId();

    /**
     * The token this connection signed in with.
     *
     * <p>Exposed so a caller can mint a sibling connection on the same
     * identity without holding the password again -- {@code connect(token
     * .token(), ...)} takes it directly.
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

    /** Whether this connection's user holds ROLE_ADMIN. */
    default boolean isAdmin() {
        return hasRole("ROLE_ADMIN");
    }

    /**
     * Whether this connection's user holds ROLE_MANAGER.
     *
     * <p>The role that runs a study — opening and closing sessions, staging
     * allocations, minting passcodes.
     */
    default boolean isManager() {
        return hasRole("ROLE_MANAGER");
    }

    /** The marketplace this connection was pointed at, from its endpoint. */
    long endpointMarketplaceId();

    String endpointUrl();
}
