package net.onelitefeather.blackhole.velocity.component;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.NotNull;

/**
 * How rich a staff message is: {@link #RICH} for a player's client (hover and click events), {@link #PLAIN}
 * for everything else, such as the console, where the details are written inline instead.
 */
public enum Mode {
    RICH,
    PLAIN;

    public static @NotNull Mode of(@NotNull Audience receiver) {
        return receiver instanceof Player ? RICH : PLAIN;
    }
}
