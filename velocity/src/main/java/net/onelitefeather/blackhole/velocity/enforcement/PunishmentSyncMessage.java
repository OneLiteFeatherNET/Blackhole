package net.onelitefeather.blackhole.velocity.enforcement;

/**
 * Mirrors the backend's {@code net.onelitefeather.blackhole.backend.punishment.PunishmentSyncMessage}
 * field-for-field - the shape the backend's events describe a punishment in. Built from the
 * {@code punishment.*} events and from the backend profile, hand-shared, not part of the OpenAPI client:
 * it is the proxy's own representation of one active punishment, not a public API contract.
 *
 * @param owner                the hashed owner
 * @param section              the key of the punishment's section
 * @param enforcement          MUTE, BAN or SERVER; may be null if the backend no longer knows the section
 * @param state                {@code SET} or {@code CLEARED}
 * @param punishmentIdentifier the punishment this message concerns
 * @param templateIdentifier   the template, null for system punishments
 * @param reason               the MiniMessage reason shown to the player
 * @param expiresAt            epoch millis the punishment ends at, null if permanent
 * @param automatic            whether the ELO system applied it; its reason is stored in English, so
 *                             the screens show a translated one instead (absent in old messages: false)
 */
public record PunishmentSyncMessage(
        String owner,
        String section,
        String enforcement,
        String state,
        String punishmentIdentifier,
        String templateIdentifier,
        String reason,
        Long expiresAt,
        boolean automatic
) {
    public static final String STATE_SET = "SET";
    public static final String STATE_CLEARED = "CLEARED";

    /**
     * @return whether this message announces an active punishment (as opposed to a lifted one)
     */
    public boolean isSet() {
        return STATE_SET.equals(this.state);
    }

    /**
     * @param nowMillis the current time
     * @return whether the punishment is active at that time: announced as set and not past its expiry
     */
    public boolean isActiveAt(long nowMillis) {
        return isSet() && (this.expiresAt == null || this.expiresAt > nowMillis);
    }
}
