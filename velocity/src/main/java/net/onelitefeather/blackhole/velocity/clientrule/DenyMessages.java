package net.onelitefeather.blackhole.velocity.clientrule;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * What a player refused by a rule sees. English only, whatever the player's locale, plain text (no
 * formatting is interpreted): the text is written on the rule, or the fixed default of its kind.
 */
public final class DenyMessages {

    static final String CLIENT = "This client or its modifications are not allowed on this server.";
    static final String VERSION = "This Minecraft version is not allowed on this server.";
    static final String COMMAND = "This command is not allowed here.";

    private DenyMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static @NotNull Component of(@NotNull ClientRule rule) {
        return Component.text(text(rule));
    }

    static String text(ClientRule rule) {
        if (rule.denyMessage() != null && !rule.denyMessage().isBlank()) {
            return rule.denyMessage();
        }
        return switch (rule.kind()) {
            case BRAND, MOD, CHANNEL -> CLIENT;
            case PROTOCOL -> VERSION;
            case COMMAND -> COMMAND;
        };
    }
}
