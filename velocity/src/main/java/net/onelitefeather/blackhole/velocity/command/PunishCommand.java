package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.velocity.component.Mode;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncHandler;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidence;
import net.onelitefeather.blackhole.velocity.enforcement.ProfileAdapter;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * The staff commands that punish a player by template key: {@code /ban} (BAN and SERVER sections),
 * {@code /mute} (MUTE sections) and {@code /punish} (any section). Staff only pick a template; every
 * punishment they create is temporary, permanent ones only result from the backend's escalation.
 */
public final class PunishCommand {

    private final PunishService service;
    private final SectionRegistry sections;
    private final PunishmentSyncHandler syncHandler;
    private final Clock clock;
    private final BlackholeConfig config;
    private final MuteNotices notices;

    @Inject
    public PunishCommand(
            @NotNull PunishService service, @NotNull SectionRegistry sections,
            @NotNull PunishmentSyncHandler syncHandler, @NotNull Clock clock, @NotNull BlackholeConfig config,
            @NotNull MuteNotices notices
    ) {
        this.service = service;
        this.sections = sections;
        this.syncHandler = syncHandler;
        this.clock = clock;
        this.config = config;
        this.notices = notices;
    }

    @Command(value = "blackhole <user> ban <template>", requiredSender = Player.class)
    @Command(value = "ban <user> <template>", requiredSender = Player.class)
    @Permission("blackhole.ban")
    @CommandDescription("Ban a player with a template of a ban or server section")
    public void banPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument(value = "template", suggestions = "ban-templates") @NotNull String template
    ) {
        apply(context.sender(), user, template, PunishService.BAN_KINDS, "ban", "punishment.error.ban");
    }

    @Command(value = "blackhole <user> mute <template>", requiredSender = Player.class)
    @Command(value = "mute <user> <template>", requiredSender = Player.class)
    @Permission("blackhole.mute")
    @CommandDescription("Mute a player with a template of a mute section")
    public void mutePlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument(value = "template", suggestions = "mute-templates") @NotNull String template
    ) {
        apply(context.sender(), user, template, PunishService.MUTE_KINDS, "mute", "punishment.error.mute");
    }

    @Command(value = "blackhole <user> punish <template>", requiredSender = Player.class)
    @Command(value = "punish <user> <template>", requiredSender = Player.class)
    @Permission("blackhole.punish")
    @CommandDescription("Punish a player with any template")
    public void punishPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument(value = "template", suggestions = "all-templates") @NotNull String template
    ) {
        apply(context.sender(), user, template, PunishService.ANY_KINDS, "punish", "punishment.error.punish");
    }

    @Suggestions("ban-templates")
    public List<String> suggestBanTemplates(CommandContext<Player> context, CommandInput input) {
        return this.service.suggest(input.readString(), PunishService.BAN_KINDS);
    }

    @Suggestions("mute-templates")
    public List<String> suggestMuteTemplates(CommandContext<Player> context, CommandInput input) {
        return this.service.suggest(input.readString(), PunishService.MUTE_KINDS);
    }

    @Suggestions("all-templates")
    public List<String> suggestAllTemplates(CommandContext<Player> context, CommandInput input) {
        return this.service.suggest(input.readString(), PunishService.ANY_KINDS);
    }

    private void apply(Player sender, ResolvedTarget target, String templateKey, Set<EnforcementKind> kinds, String command, String failureKey) {
        PunishService.ApplyOutcome outcome = this.service.apply(target.ownerHash(), target.uuid(), templateKey, sender.getUniqueId(), kinds, false);
        switch (outcome) {
            case PunishService.ApplyOutcome.Applied applied -> applied(sender, target, applied);
            case PunishService.ApplyOutcome.TemplateNotFound missing -> sender.sendMessage(
                    StaffMessages.templateNotFound(command, target.name(), missing.key(), missing.similar(), Mode.of(sender)));
            case PunishService.ApplyOutcome.WrongSection wrong ->
                    sender.sendMessage(StaffMessages.wrongSection(target.name(), wrong.key(), wrong.kind(), Mode.of(sender)));
            case PunishService.ApplyOutcome.Failed failed -> sender.sendMessage(failed.status() == 409
                    ? Component.translatable("punishment.error.conflict")
                    : Component.translatable(failureKey));
        }
    }

    private void evidenceFeedback(Player sender, ChatEvidence.Result evidence) {
        switch (evidence.status()) {
            case ATTACHED -> sender.sendMessage(Component.translatable("punishment.evidence.attached")
                    .arguments(Component.text(evidence.lines())));
            case FAILED -> sender.sendMessage(Component.translatable("punishment.evidence.failed"));
            case NONE -> {
                // capture is off or there was nothing buffered: staff need no extra line
            }
        }
    }

    private void applied(Player sender, ResolvedTarget target, PunishService.ApplyOutcome.Applied applied) {
        PunishmentApplyResponse response = applied.response();
        String section = applied.section().key();
        PunishmentSyncMessage punished = ProfileAdapter.toMessage(target.ownerHash(), section, response.getPunishment(), this.sections);

        // Enforce what is in force now: after an escalation that is the permanent punishment.
        PunishmentSyncMessage inForce = response.getEscalation() == null
                ? punished
                : ProfileAdapter.toMessage(target.ownerHash(), section, response.getEscalation().getPunishment(), this.sections);
        this.syncHandler.accept(inForce);

        if (applied.section().enforcement() == EnforcementKind.MUTE && target.onlinePlayer() != null) {
            Player muted = target.onlinePlayer();
            // Same cooldown as chat, so the first chat attempt after this shows only the reminder.
            muted.sendMessage(this.notices.notice(
                    target.ownerHash(), inForce, PunishmentScreens.localeOf(muted), 0));
        }
        sender.sendMessage(StaffMessages.applied(target.name(), applied.section(), response, punished, this.clock,
                this.config.getZone(), Permissions.of(sender), Mode.of(sender)));
        evidenceFeedback(sender, applied.evidence());
    }
}
