package fm.internal;
/**
 * The stream protocol version, as the server announces it on connect.
 *
 * @param version the protocol version the server announced
 */

public record Version(int version) {}
