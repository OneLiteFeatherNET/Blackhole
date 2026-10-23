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
import net.onelitefeather.blackhole.velocity.punish.PunishService.RevokeOutcome;
import net.onelitefeather.blackhole.velocity.punish.StaffLinkService.LinkOutcome;
import net.onelitefeather.blackhole.velocity.punish.StaffLinkService.WhoamiOutcome;
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
import java.util.Collection;
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

    /** The answer to a command that needs a player, such as {@code /ban}, sent from the console. */
    public static @NotNull Component playerOnly() {
        return Component.translatable("staff.error.player_only");
    }

    /** The answer to any staff command while the proxy has no complete credentials for the backend. */
    public static @NotNull Component authNotConfigured() {
        return Component.translatable("staff.error.auth_not_configured");
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

    /**
     * The answer to lifting a punishment, whichever way it ended. Every failure names the next step: the
     * sections the command accepts, ready to click, or the {@code info} command that shows what is active.
     *
     * @param command   the command that was used ({@code unban} or {@code unmute})
     * @param requested the section key the sender asked for
     * @param sections  the sections that command accepts, to name and to suggest
     */
    public static @NotNull Component revoked(
            @NotNull RevokeOutcome outcome, @NotNull String targetName, @NotNull String command, @NotNull String requested,
            @NotNull Collection<SectionInfo> sections, @NotNull Mode mode
    ) {
        boolean mute = command.equals("unmute");
        Component player = Fmt.player(targetName, mode);
        return switch (outcome) {
            case RevokeOutcome.Revoked revoked -> Component.translatable(
                    mute ? "staff.revoke.unmute.success" : "staff.revoke.unban.success",
                    player, Component.text(sectionName(requested, sections)));
            case RevokeOutcome.NothingActive none -> Component.join(JoinConfiguration.newlines(),
                    Component.translatable(mute ? "staff.revoke.unmute.none_active" : "staff.revoke.unban.none_active",
                            player, Component.text(sectionName(none.section(), sections))),
                    Component.translatable("staff.revoke.info_hint", suggestion(
                            "/blackhole " + targetName + " info", "/blackhole " + targetName + " info", mode)));
            case RevokeOutcome.PermanentNotAllowed permanent -> Component.join(JoinConfiguration.newlines(),
                    Component.translatable("staff.revoke.permanent", player, Component.text(sectionName(permanent.section(), sections))),
                    Component.translatable("staff.revoke.permanent.hint"));
            case RevokeOutcome.UnknownSection unknown -> Component.join(JoinConfiguration.newlines(),
                    Component.translatable("staff.revoke.section.unknown", Component.text(unknown.section())),
                    sectionList(command, targetName, sections, mode));
            case RevokeOutcome.WrongSection wrong -> Component.join(JoinConfiguration.newlines(),
                    Component.translatable("staff.revoke.section.wrong", Component.text(wrong.section()), Component.text("/" + command)),
                    sectionList(command, targetName, sections, mode));
            case RevokeOutcome.Failed ignored -> Component.translatable(mute ? "staff.revoke.unmute.failed" : "staff.revoke.unban.failed");
        };
    }

    /** The answer to {@code /blackhole link}, whichever way it ended. */
    public static @NotNull Component linked(@NotNull LinkOutcome outcome) {
        return switch (outcome) {
            case LinkOutcome.Linked linked -> linked.displayName() == null
                    ? Component.translatable("staff.link.success.unnamed")
                    : Component.translatable("staff.link.success", Component.text(linked.displayName()));
            case LinkOutcome.InvalidCode ignored -> Component.translatable("staff.link.invalid");
            case LinkOutcome.Failed ignored -> Component.translatable("staff.link.failed");
        };
    }

    /** The answer to {@code /blackhole whoami}. */
    public static @NotNull Component whoami(@NotNull WhoamiOutcome outcome) {
        return switch (outcome) {
            case WhoamiOutcome.Linked linked -> linked.displayName() == null
                    ? Component.translatable("staff.whoami.linked.unnamed")
                    : Component.translatable("staff.whoami.linked", Component.text(linked.displayName()));
            case WhoamiOutcome.NotLinked ignored -> Component.translatable("staff.whoami.unlinked");
            case WhoamiOutcome.Failed ignored -> Component.translatable("staff.whoami.failed");
        };
    }

    private static String sectionName(String key, Collection<SectionInfo> sections) {
        return sections.stream().filter(section -> section.key().equals(key)).map(SectionInfo::displayName).findFirst().orElse(key);
    }

    private static Component sectionList(String command, String targetName, Collection<SectionInfo> sections, Mode mode) {
        if (sections.isEmpty()) {
            return Component.translatable("staff.revoke.sections.none");
        }
        List<Component> suggestions = sections.stream()
                .map(section -> suggestion(section.key(), "/" + command + " " + targetName + " " + section.key(), mode))
                .toList();
        return Component.translatable("staff.revoke.sections", Component.join(JoinConfiguration.separator(Component.text(", ")), suggestions));
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
