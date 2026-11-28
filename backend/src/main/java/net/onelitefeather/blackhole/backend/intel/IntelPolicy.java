package net.onelitefeather.blackhole.backend.intel;

/** What happens to a login, per classification. The declaration order is the strictness order. */
public enum IntelPolicy {
    /** Nothing happens. */
    ALLOW,
    /** The login proceeds, a hit is recorded and staff are told. */
    FLAG,
    /** The login is refused. */
    DENY;

    /** @return the stricter of the two policies: DENY over FLAG over ALLOW */
    public IntelPolicy stricterOf(IntelPolicy other) {
        return this.compareTo(other) >= 0 ? this : other;
    }
}
