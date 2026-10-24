package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What a client rule matches. The first three are chosen by the client and can be faked; the proxy
 * observes the protocol version and the command itself.
 */
@Serdeable
public enum ClientRuleKind {

    /** The {@code minecraft:brand} string. */
    BRAND(true, true),

    /** A mod id from the legacy Forge mod list (Forge up to 1.12). */
    MOD(true, true),

    /** A plugin channel the client registered. */
    CHANNEL(true, true),

    /** A range of protocol versions. */
    PROTOCOL(false, false),

    /** The first word of a command line. */
    COMMAND(true, false);

    private final boolean usesPatterns;
    private final boolean spoofable;

    ClientRuleKind(boolean usesPatterns, boolean spoofable) {
        this.usesPatterns = usesPatterns;
        this.spoofable = spoofable;
    }

    /** Whether the rule holds glob patterns (every kind but {@link #PROTOCOL}). */
    public boolean usesPatterns() {
        return this.usesPatterns;
    }

    /** Whether the client controls the signal, so a punishment on it needs an explicit acknowledgement. */
    public boolean spoofable() {
        return this.spoofable;
    }
}
