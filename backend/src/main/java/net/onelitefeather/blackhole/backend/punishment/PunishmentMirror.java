package net.onelitefeather.blackhole.backend.punishment;

/**
 * Write side of the proxy-facing mirror of active punishments, one entry per owner and section.
 */
public interface PunishmentMirror {

    /**
     * Records that {@code owner} has an active punishment in {@code section}.
     *
     * @param expiresAt epoch millis the punishment ends at, or {@code null} if permanent
     * @param automatic whether the ELO system applied it, so its stored reason is an English placeholder
     *                  a proxy replaces with a translated text
     */
    void set(String section, String enforcement, String owner, String punishmentIdentifier, String templateIdentifier, String reason, Long expiresAt, boolean automatic);

    /**
     * Records that {@code owner} no longer has an active punishment in {@code section}.
     */
    void clear(String section, String enforcement, String owner, String punishmentIdentifier);
}
