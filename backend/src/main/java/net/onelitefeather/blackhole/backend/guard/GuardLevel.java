package net.onelitefeather.blackhole.backend.guard;

/**
 * The join guard level, ordered from least to most restrictive. The effective level of the network is the higher of
 * the manual and the unattended level.
 */
public enum GuardLevel {
    /** Only area bans and the VPN policy apply. */
    OFF,
    /** A login from a VPN, hosting or proxy address is refused. */
    STRICT,
    /** STRICT, and only owners known to the network may join. */
    LOCKDOWN;

    /** The more restrictive of the two levels. */
    public GuardLevel stricterOf(GuardLevel other) {
        return this.compareTo(other) >= 0 ? this : other;
    }
}
