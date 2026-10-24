package net.onelitefeather.blackhole.velocity.enforcement;

import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The one place that decides what a muted player is told, for every path that talks to them (the
 * command that applied the mute and each chat attempt): the full notice once per punishment, the
 * one-line reminder while the cooldown runs. Sync messages never tell the player anything.
 */
public final class MuteNotices {

    private final PunishmentScreens screens;
    private final MuteNoticeCooldown cooldown;

    public MuteNotices(@NotNull PunishmentScreens screens, @NotNull MuteNoticeCooldown cooldown) {
        this.screens = screens;
        this.cooldown = cooldown;
    }

    /**
     * @param owner         the muted player's owner hash
     * @param mute          the mute in force
     * @param locale        the player's locale
     * @param evidenceCount how many pieces of evidence the mute has
     * @return the full notice if it is due for this punishment, the reminder otherwise
     */
    public @NotNull Component notice(
            @NotNull String owner, @NotNull PunishmentSyncMessage mute, @NotNull Locale locale, long evidenceCount
    ) {
        return this.cooldown.fullNoticeDue(owner, mute.punishmentIdentifier())
                ? this.screens.screen(mute, locale, PunishmentScreens.Surface.CHAT, false, evidenceCount)
                : this.screens.muteReminder(mute, locale);
    }

    /**
     * @param owner the owner hash of a player who left
     */
    public void forget(@NotNull String owner) {
        this.cooldown.forget(owner);
    }
}
