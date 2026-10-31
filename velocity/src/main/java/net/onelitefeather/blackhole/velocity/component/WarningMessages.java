package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a player and a staff member are told about warnings. English plain text whatever the client locale,
 * like the kick and ban screens: a warning is short and its acknowledge line is a command, so it carries no
 * translatable structure. Every single-line message starts with the Blackhole prefix, like the other chat messages.
 */
public final class WarningMessages {

    private WarningMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * The warning as the player sees it, in chat, with the line that acknowledges it. Shown when the warning
     * is given, and again on each login while it is still pending. The prefix is on the first line only.
     *
     * @param reason the template's reason, or null when the backend could not be read: the default text is shown
     * @param id     the warning's id, the acknowledge line runs {@code /blackhole warning ack <id>}
     */
    public static @NotNull Component warning(@Nullable String reason, @NotNull String id) {
        Component header = BlackholeTheme.prefixed(reason == null
                ? Component.text("You received a warning.", NamedTextColor.GOLD)
                : Component.text("You received a warning: " + reason, NamedTextColor.GOLD));
        Component acknowledge = Component.text("[Click here to acknowledge the warning]", NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/blackhole warning ack " + id))
                .hoverEvent(HoverEvent.showText(Component.text("Confirm that you have read this warning")));
        return Component.join(JoinConfiguration.newlines(), header, acknowledge);
    }

    /** @return the line after the player confirmed a warning */
    public static @NotNull Component acknowledged() {
        return BlackholeTheme.prefixed(Component.text("The warning is acknowledged. Thank you.", NamedTextColor.GREEN));
    }

    /** @return the line when a player tries to confirm a warning that is not theirs */
    public static @NotNull Component notYours() {
        return BlackholeTheme.prefixed(Component.text("That warning is not yours.", NamedTextColor.RED));
    }

    /** @return the line when the warning does not exist */
    public static @NotNull Component unknown() {
        return BlackholeTheme.prefixed(Component.text("No such warning.", NamedTextColor.RED));
    }

    /** @return the line when the acknowledgement could not be read or sent; the player may try again */
    public static @NotNull Component failed() {
        return BlackholeTheme.prefixed(Component.text("The warning could not be acknowledged. Try again in a moment.", NamedTextColor.RED));
    }

    /** @return the usage line of {@code /warn} */
    public static @NotNull Component usage() {
        return BlackholeTheme.prefixed(Component.text("Usage: /warn <player> <template>", NamedTextColor.YELLOW));
    }

    /** @return the line for staff after a warning was given */
    public static @NotNull Component applied(@NotNull String player, @NotNull String template) {
        return BlackholeTheme.prefixed(Component.text("Warned " + player + " with " + template + ".", NamedTextColor.GREEN));
    }

    /** @return the line when the template's next step is not a warning, so the backend applied nothing */
    public static @NotNull Component stepRequired(@NotNull String template) {
        return BlackholeTheme.prefixed(Component.text("The next step of " + template + " is not a warning, so nothing was applied. Use /punish for that step.",
                NamedTextColor.RED));
    }

    /** @return the line when the backend refused the warning for another reason */
    public static @NotNull Component failedApply() {
        return BlackholeTheme.prefixed(Component.text("The warning could not be applied. Try again; if it keeps failing, check the backend.", NamedTextColor.RED));
    }
}
