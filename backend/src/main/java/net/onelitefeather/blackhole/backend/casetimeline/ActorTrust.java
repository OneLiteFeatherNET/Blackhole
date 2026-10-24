package net.onelitefeather.blackhole.backend.casetimeline;

/**
 * How far an event's actor can be trusted. A reader must never present {@link #UNVERIFIED} like
 * {@link #VERIFIED}.
 */
@io.micronaut.serde.annotation.Serdeable
public enum ActorTrust {
    /** The actor's {@code oid} came from a human token or a live staff link. */
    VERIFIED,
    /** Only an attributed source UUID (legacy rows, unlinked in-game staff) or no actor is known. */
    UNVERIFIED,
    /** The system itself: escalation, the expiry sweeper, the reconciler, an automatic reopen. */
    SYSTEM,
    /**
     * A player wrote it (portal, later Discord): no {@code oid}, claimed through the Ban ID only. Not
     * {@link #UNVERIFIED}, which means a legacy or unlinked staff actor. Never shown with a verified badge.
     */
    PLAYER
}
