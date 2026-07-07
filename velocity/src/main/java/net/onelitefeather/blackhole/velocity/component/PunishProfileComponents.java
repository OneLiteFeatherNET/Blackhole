package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.component.tag.DurationTag;
import net.onelitefeather.phoca.metadata.Expirable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class PunishProfileComponents {

    public static @NotNull Component componentRepresentation(@NotNull String userName, @NotNull PunishProfileDTO profile) {
        return header(userName, "profile:")
                .append(Component.newline())
                .append(Component.text("Active ban: ", NamedTextColor.GRAY))
                .append(renderEntryOrNone(profile.getActiveBan()))
                .append(Component.newline())
                .append(Component.text("Active mute: ", NamedTextColor.GRAY))
                .append(renderEntryOrNone(profile.getActiveChatBan()));
    }

    public static @NotNull Component historyRepresentation(@NotNull String userName, @NotNull PunishProfileDTO profile) {
        Component body = header(userName, "history:");
        List<PunishEntryDTO> history = profile.getHistory();
        if (history == null || history.isEmpty()) {
            return body.append(Component.newline()).append(Component.text("No history.", NamedTextColor.GRAY));
        }

        for (PunishEntryDTO entry : history) {
            body = body.append(Component.newline())
                    .append(Component.text("- ", NamedTextColor.GRAY))
                    .append(renderEntry(entry));
        }
        return body;
    }

    private static @NotNull Component header(@NotNull String userName, @NotNull String suffix) {
        return Component.text(userName)
                .append(Component.text('s'))
                .append(Component.space())
                .append(Component.text(suffix));
    }

    private static @NotNull Component renderEntryOrNone(@Nullable PunishEntryDTO entry) {
        return entry == null ? Component.text("None", NamedTextColor.GREEN) : renderEntry(entry);
    }

    private static @NotNull Component renderEntry(@NotNull PunishEntryDTO entry) {
        Component reason = MiniMessage.miniMessage().deserialize(entry.getTemplate().getReason(), expirationResolver(entry));
        return Component.text("[" + entry.getTemplate().getType() + "] ", NamedTextColor.RED)
                .append(reason);
    }

    private static @NotNull TagResolver expirationResolver(@NotNull PunishEntryDTO entry) {
        Object expirationDate = entry.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE);
        if (expirationDate instanceof Number number) {
            return DurationTag.resolver(number.longValue());
        }
        return TagResolver.empty();
    }

    private PunishProfileComponents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
