package net.onelitefeather.blackhole.discord.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The language of a reply. English is the default; a user whose Discord client is German gets German.
 */
public enum Lang {
    EN, DE;

    /**
     * @param discordLocale the user's Discord locale tag such as {@code de} or {@code en-US}, or null
     * @return German for any {@code de} locale, English otherwise
     */
    public static @NotNull Lang fromDiscordLocale(@Nullable String discordLocale) {
        if (discordLocale != null && discordLocale.toLowerCase(Locale.ROOT).startsWith("de")) {
            return DE;
        }
        return EN;
    }
}
