package net.onelitefeather.blackhole.backend.security;

/** Whether a verified principal is a signed-in person or an application using the client credentials flow. */
public enum ActorKind {
    /** A delegated token: a staff member signed in (the token carries {@code scp}). */
    HUMAN,
    /** An app-only token, such as the Velocity proxy. */
    SERVICE
}
