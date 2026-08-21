package fm;

/**
 * What an implementation says when asked for a role it does not fill.
 *
 * <p>The message names the implementation as well as the operation, because
 * "does not support openSession" on its own sends a reader looking for a
 * server-side permission problem. Naming the class says immediately that the
 * object in hand is a fake, or a provider that models only part of the API.
 *
 * <p>A class rather than a private method on each interface: the roles all need
 * it and interfaces cannot share private members.
 */
final class Unsupported {

    private Unsupported() {
    }

    static UnsupportedOperationException by(Object implementation, String operation) {
        return new UnsupportedOperationException(
                implementation.getClass().getName() + " does not support " + operation + "(...)");
    }
}
