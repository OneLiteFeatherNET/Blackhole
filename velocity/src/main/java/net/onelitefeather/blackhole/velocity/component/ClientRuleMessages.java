package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleNotice;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The staff-facing texts of the client rules (localised de/en like every other staff text). What a
 * refused player sees is not here: it is English only and lives on the rule.
 */
public final class ClientRuleMessages {

    private ClientRuleMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The in-game notice for staff holding {@value Permissions#CLIENT_RULES_NOTIFY}. */
    public static @NotNull Component notice(@NotNull ClientRuleNotice notice) {
        return Component.translatable("clientrule.notice",
                Component.text(notice.rule()), Component.text(notice.player()),
                Component.translatable("clientrule.kind." + notice.kind().toLowerCase(Locale.ROOT)),
                Component.translatable("clientrule.action." + notice.action().toLowerCase(Locale.ROOT)),
                Component.translatable(notice.enforced() ? "clientrule.enforced.yes" : "clientrule.enforced.no"));
    }
}
