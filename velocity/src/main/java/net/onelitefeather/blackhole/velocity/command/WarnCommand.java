package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.Mode;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import net.onelitefeather.blackhole.velocity.component.WarningMessages;
import net.onelitefeather.blackhole.velocity.enforcement.WarningHandler;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code /warn <player> <template>}: applies a template whose next step must be a warning. The backend
 * refuses the apply (422) when the ladder's next step is a ban, a kick or a punishment, so {@code /warn}
 * never escalates; the staff member then uses {@code /punish} for that step. The apply runs on the async
 * executor and never touches the punishment mirror: a warning is not enforced.
 */
public final class WarnCommand {

    private final PunishService service;
    private final WarningHandler warnings;

    @Inject
    public WarnCommand(@NotNull PunishService service, @NotNull WarningHandler warnings) {
        this.service = service;
        this.warnings = warnings;
    }

    @Command(value = "blackhole <user> warn <template>", requiredSender = Player.class)
    @Command(value = "warn <user> <template>", requiredSender = Player.class)
    @Permission(Permissions.WARN)
    @CommandDescription("Warn a player with a template whose next step is a warning")
    public void warnPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument(value = "template", suggestions = "warn-templates") @NotNull String template
    ) {
        handle(context.sender(), user, template);
    }

    @Suggestions("warn-templates")
    public List<String> suggestTemplates(CommandContext<Player> context, CommandInput input) {
        return this.service.suggest(input.readString(), PunishService.ANY_KINDS);
    }

    /** The behaviour of the command without the command framework, for tests. */
    void handle(@NotNull Player sender, @NotNull ResolvedTarget user, String template) {
        if (template == null || template.isBlank()) {
            sender.sendMessage(WarningMessages.usage());
            return;
        }
        String key = template.trim();
        this.warnings.async(() -> send(sender, user, key,
                this.service.apply(user.ownerHash(), user.uuid(), key, sender.getUniqueId(), PunishService.ANY_KINDS, true)));
    }

    private static void send(Player sender, ResolvedTarget user, String key, PunishService.ApplyOutcome outcome) {
        switch (outcome) {
            case PunishService.ApplyOutcome.Applied ignored -> sender.sendMessage(WarningMessages.applied(user.name(), key));
            case PunishService.ApplyOutcome.TemplateNotFound missing -> sender.sendMessage(
                    StaffMessages.templateNotFound("warn", user.name(), missing.key(), missing.similar(), Mode.of(sender)));
            case PunishService.ApplyOutcome.WrongSection wrong ->
                    sender.sendMessage(StaffMessages.wrongSection(user.name(), wrong.key(), wrong.kind(), Mode.of(sender)));
            case PunishService.ApplyOutcome.Failed failed -> sender.sendMessage(failed.status() == 422
                    ? WarningMessages.stepRequired(key)
                    : WarningMessages.failedApply());
        }
    }
}
