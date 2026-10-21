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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PunishProfileComponents {

    public static @NotNull Component componentRepresentation(@NotNull String userName, @NotNull PunishProfileDTO profile) {
        Component body = header(userName, "profile:");
        Map<String, PunishEntryDTO> active = profile.getActive() == null ? Map.of() : new TreeMap<>(profile.getActive());
        if (active.isEmpty()) {
            return body.append(Component.newline()).append(Component.text("No active punishments.", NamedTextColor.GREEN));
        }
        for (Map.Entry<String, PunishEntryDTO> entry : active.entrySet()) {
            body = body.append(Component.newline())
                    .append(Component.text(entry.getKey() + ": ", NamedTextColor.GRAY))
                    .append(renderEntry(entry.getValue()));
        }
        return body;
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

    private static @NotNull Component renderEntry(@NotNull PunishEntryDTO entry) {
        String reason = entry.getTemplate() != null ? entry.getTemplate().getReason() : null;
        if (reason == null && entry.getMetaData() != null && entry.getMetaData().get("reason") instanceof String fromMeta) {
            reason = fromMeta;
        }
        Component text = reason == null
                ? Component.translatable("punishment.reason.default")
                : MiniMessage.miniMessage().deserialize(reason, expirationResolver(entry));
        String section = entry.getSection() == null ? "?" : entry.getSection();
        String kind = Boolean.TRUE.equals(entry.getPermanent()) ? "permanent" : "temporary";
        return Component.text("[" + section + ", " + kind + "] ", NamedTextColor.RED).append(text);
    }

    private static @NotNull TagResolver expirationResolver(@NotNull PunishEntryDTO entry) {
        if (entry.getMetaData() != null && entry.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE) instanceof Number number) {
            return DurationTag.resolver(number.longValue());
        }
        return TagResolver.empty();
    }

    private PunishProfileComponents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
