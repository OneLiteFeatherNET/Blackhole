package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.velocity.component.tag.DurationTag;
import net.onelitefeather.blackhole.velocity.punish.TargetNotFoundException;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The messages a staff member gets as feedback of the punish commands. Each is one component of several
 * lines, built from resolved data only, so it needs no proxy. The wording and colours live in the bundles
 * ({@code bh:} tags); this class adds what the bundles cannot: hover, click and the optional parts.
 * {@link Mode#PLAIN} receivers get the same content without events, with the hover details written inline.
 */
public final class StaffMessages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String NONE = "-";

    private StaffMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * What a staff member sees after applying a template: who was punished where, the step and when it ends,
     * the score change and the short id; an extra warning line if the deduction escalated to a permanent
     * punishment; and an undo button if the receiver may lift what is now in force.
     *
     * @param punished the punishment as applied, which carries its end
     */
    public static @NotNull Component applied(
            @NotNull String targetName, @NotNull SectionInfo section, @NotNull PunishmentApplyResponse response,
            @NotNull PunishmentSyncMessage punished, @NotNull Clock clock, @NotNull ZoneId zone,
            @NotNull Permissions permissions, @NotNull Mode mode
    ) {
        PunishEntryDTO punishment = response.getPunishment();
        PunishTemplateResponse template = punishment == null ? null : punishment.getTemplate();
        boolean escalated = response.getEscalation() != null;
        boolean permanentInForce = escalated || (punishment != null && Boolean.TRUE.equals(punishment.getPermanent()));

        Component header = Component.translatable("staff.applied.header", Fmt.player(targetName, mode), Fmt.section(section));
        if (mode == Mode.RICH && permissions.mayUndo(section.enforcement(), permanentInForce)) {
            header = header.append(Component.space()).append(undoButton(targetName, section));
        }

        String reason = template == null || template.getReason() == null ? NONE : MINI.stripTags(template.getReason());
        String templateKey = template == null || template.getKey() == null ? NONE : template.getKey();
        String identifier = punishment == null ? null : punishment.getIdentifier();
        Component shortId = PunishmentScreens.shortId(identifier) == null ? Component.text(NONE) : Fmt.shortId(identifier, mode);
        Component end = punished.expiresAt() == null
                ? Component.translatable("duration.permanent")
                : Fmt.time(Instant.ofEpochMilli(punished.expiresAt()), zone);
        Component remaining = punished.expiresAt() == null
                ? Component.translatable("duration.permanent")
                : DurationTag.format(Duration.ofMillis(punished.expiresAt() - clock.millis()));

        Component step = Component.text(response.getStep() + "/" + response.getStepCount());
        List<Component> lines = new ArrayList<>();
        lines.add(header);
        if (mode == Mode.RICH) {
            Component hover = Component.translatable("staff.hover.punishment", Component.text(reason), Component.text(templateKey), shortId, end);
            lines.add(Component.translatable("staff.applied.step", step.hoverEvent(HoverEvent.showText(hover)), remaining));
        } else if (punished.expiresAt() == null) {
            lines.add(Component.translatable("staff.applied.step", step, remaining));
        } else {
            lines.add(Component.translatable("staff.applied.step.plain", step, remaining, end));
        }
        lines.add(Component.translatable("staff.applied.score", Component.text(response.getScoreBefore()),
                Component.text(response.getScoreAfter()), Fmt.scoreDelta(response.getScoreBefore(), response.getScoreAfter())));
        lines.add(mode == Mode.RICH
                ? Component.translatable("staff.applied.id", shortId)
                : Component.translatable("staff.applied.id.plain", shortId, Component.text(templateKey), Component.text(reason)));
        if (escalated) {
            Component warning = Component.translatable("staff.applied.escalated", Fmt.section(section),
                    Component.text(response.getEscalation().getResultingScore()));
            if (!permissions.mayPardonPermanent()) {
                warning = warning.append(Component.translatable("staff.applied.escalated.hint"));
            }
            lines.add(warning);
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * The answer to a template key that does not exist: the keys of the right kind that resemble it, as
     * suggestions the receiver can click, or the hint that none match.
     *
     * @param command the command that was used ({@code ban}, {@code mute} or {@code punish})
     * @param similar the matching keys, best first
     */
    public static @NotNull Component templateNotFound(
            @NotNull String command, @NotNull String playerName, @NotNull String key, @NotNull List<String> similar, @NotNull Mode mode
    ) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("punishment.error.template.not_found", Component.text(key)));
        if (similar.isEmpty()) {
            lines.add(Component.translatable("punishment.error.template.no_match", Component.text(key)));
        } else {
            List<Component> suggestions = similar.stream()
                    .map(candidate -> suggestion(candidate, "/" + command + " " + playerName + " " + candidate, mode))
                    .toList();
            lines.add(Component.translatable("punishment.error.template.similar",
                    Component.join(JoinConfiguration.separator(Component.text(", ")), suggestions)));
        }
        lines.add(Component.translatable("punishment.error.template.tab_hint"));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * The answer to a template that exists but belongs to another kind of section: it names the command
     * that fits, ready to be put into the chat input.
     *
     * @param kind the kind of the template's section, {@code null} if the proxy does not know the section
     */
    public static @NotNull Component wrongSection(
            @NotNull String playerName, @NotNull String key, @Nullable EnforcementKind kind, @NotNull Mode mode
    ) {
        if (kind == null) {
            return Component.translatable("punishment.error.template.unknown_section", Component.text(key));
        }
        boolean mute = kind == EnforcementKind.MUTE;
        String command = "/" + (mute ? "mute" : "ban") + " " + playerName + " " + key;
        return Component.translatable("punishment.error.template.wrong_section", Component.text(key),
                Component.translatable(mute ? "staff.kind.mute" : "staff.kind.ban"), suggestion(command, command, mode));
    }

    /**
     * The usage line for a command called with wrong syntax.
     *
     * @param syntax the command as Cloud prints it, such as {@code ban <user> <template>}
     */
    public static @NotNull Component usage(@NotNull String syntax, @NotNull Mode mode) {
        // The argument is called "user" in the command definitions; staff are told what to type.
        String shown = "/" + syntax.replace("<user>", "<player>");
        int firstArgument = shown.indexOf(" <");
        String base = (firstArgument < 0 ? shown : shown.substring(0, firstArgument)) + " ";
        return Component.join(JoinConfiguration.newlines(),
                Component.translatable("staff.error.syntax"),
                Component.translatable("staff.error.usage", suggestion(shown, base, mode)));
    }

    /**
     * The answer to an argument Cloud could not parse: a target nobody knows gets its own text, anything
     * else is shown as Cloud describes it.
     */
    public static @NotNull Component argumentError(@NotNull Throwable cause) {
        if (cause instanceof TargetNotFoundException notFound) {
            return Component.join(JoinConfiguration.newlines(),
                    Component.translatable("staff.error.target_not_found", Component.text(notFound.input())),
                    Component.translatable("staff.error.target_hint"));
        }
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return Component.translatable("staff.error.argument", Component.text(detail));
    }

    private static Component undoButton(String targetName, SectionInfo section) {
        String command = (section.enforcement() == EnforcementKind.MUTE ? "/unmute " : "/unban ") + targetName + " " + section.key();
        return Component.translatable("staff.button.undo")
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.translatable("staff.hover.undo", Component.text(command))));
    }

    /** Text that puts {@code command} into the chat input when clicked; plain text for the console. */
    private static Component suggestion(String text, String command, Mode mode) {
        Component component = Component.text(text);
        if (mode == Mode.PLAIN) {
            return component;
        }
        return component
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.translatable("staff.hover.suggest", Component.text(command))));
    }
}
