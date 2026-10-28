package net.onelitefeather.blackhole.backend.audit;

/** Where a staff action was made. Derived by the backend from the verified caller, never from the request body. */
public enum Surface {
    /** The team panel, a signed-in person using the panel's own client. */
    PANEL,
    /** The Discord bot, acting for a staff member. */
    DISCORD,
    /** The Velocity proxy, acting for a staff member in game. */
    IN_GAME,
    /** Any other API client with a signed-in person's token. */
    API
}
