package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import org.jetbrains.annotations.NotNull;

public final class PunishProfileComponents {

    public static @NotNull Component componentRepresentation(@NotNull String userName, @NotNull PunishProfileDTO profile) {
        Component header = Component.text(userName).append(Component.text('s')).append(Component.space()).append(Component.text("profile:"));

        return header
                .append(Component.newline());

    }

    public static @NotNull Component historyRepresentation(@NotNull String userName, @NotNull PunishProfileDTO profile) {
        Component header = Component.text(userName).append(Component.text('s')).append(Component.space()).append(Component.text("history:"));

        return header
                .append(Component.newline());

    }

    private PunishProfileComponents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
