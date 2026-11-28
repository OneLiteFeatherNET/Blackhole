package net.onelitefeather.blackhole.velocity.nats;

import java.util.List;

/**
 * The subjects the proxy listens to. They are exactly what its NATS user may subscribe to (see
 * {@code docs/nats-event-bus.md}); the server rejects any other subscription.
 */
public final class Subjects {

    private static final String PREFIX = "blackhole.events.";

    public static final String PUNISHMENT_CREATED = PREFIX + "punishment.created";
    public static final String PUNISHMENT_UPDATED = PREFIX + "punishment.updated";
    public static final String PUNISHMENT_EXPIRED = PREFIX + "punishment.expired";
    public static final String PUNISHMENT_REVOKED = PREFIX + "punishment.revoked";
    public static final String APPEAL_RESOLVED = PREFIX + "appeal.resolved";
    public static final String SECTION_CHANGED = PREFIX + "section.changed";
    public static final String REPORT_CREATED = PREFIX + "report.created";
    public static final String REPORT_FEEDBACK = PREFIX + "report.feedback";
    /** One player is to be taken off the network, a server or a section. Matched by the punishment wildcard. */
    public static final String PUNISHMENT_KICK = PREFIX + "punishment.kick";
    /** Everybody is to be taken off a server or section. */
    public static final String KICK_MASS = PREFIX + "kick.mass";
    /** A player was warned. Matched by the punishment wildcard; a warning never touches the punishment cache. */
    public static final String PUNISHMENT_WARNED = PREFIX + "punishment.warned";

    /** The subscriptions: one wildcard for the punishment events (kicks included), the others singly. */
    public static final List<String> SUBSCRIPTIONS = List.of(
            PREFIX + "punishment.*", APPEAL_RESOLVED, SECTION_CHANGED, REPORT_CREATED, REPORT_FEEDBACK,
            KICK_MASS);

    private Subjects() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
