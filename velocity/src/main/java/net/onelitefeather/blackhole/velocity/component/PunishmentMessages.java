package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.velocity.component.tag.DurationTag;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Builds the messages shown for punishments: the text a punished player sees and the result a staff
 * member gets after applying one. Texts are translatable keys, resolved per player locale.
 */
public final class PunishmentMessages {

    private PunishmentMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * The message a punished player sees (disconnect, denied connect): the punishment's reason with
     * the remaining time available as {@code <duration>}.
     */
    public static @NotNull Component forPlayer(@NotNull PunishmentSyncMessage punishment) {
        if (punishment.reason() == null || punishment.reason().isBlank()) {
            return Component.translatable("punishment.reason.default");
        }
        TagResolver duration = punishment.expiresAt() == null ? TagResolver.empty() : DurationTag.resolver(punishment.expiresAt());
        return MiniMessage.miniMessage().deserialize(punishment.reason(), duration);
    }

    /**
     * What a staff member sees after applying a template: who was punished where, until when, which
     * step and the score change; plus a second line if the deduction escalated to a permanent punishment.
     *
     * @param punished the punishment as applied
     * @param nowMillis the current time, to render how long the punishment lasts
     */
    public static @NotNull Component applied(
            @NotNull String targetName, @NotNull SectionInfo section, @NotNull PunishmentApplyResponse response,
            @NotNull PunishmentSyncMessage punished, long nowMillis
    ) {
        Component duration = punished.expiresAt() == null
                ? Component.translatable("duration.permanent")
                : DurationTag.format(Duration.ofMillis(punished.expiresAt() - nowMillis));
        Component line = Component.translatable("punishment.result.applied").arguments(
                Component.text(targetName), Component.text(section.displayName()), duration,
                Component.text(response.getStep()), Component.text(response.getStepCount()),
                Component.text(response.getScoreBefore()), Component.text(response.getScoreAfter()));
        if (response.getEscalation() != null) {
            line = line.append(Component.newline()).append(Component.translatable("punishment.result.escalated").arguments(
                    Component.text(targetName), Component.text(section.displayName()), Component.text(response.getEscalation().getResultingScore())));
        }
        return line;
    }
}
