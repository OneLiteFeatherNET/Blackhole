package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.ReportMessages;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import net.onelitefeather.blackhole.velocity.report.ReportCategories;
import net.onelitefeather.blackhole.velocity.report.ReportService;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * {@code /report <player> <reason> [details]}: any player reports another. The command carries no
 * {@code @Permission}, because Velocity treats a permission nobody granted as missing; instead
 * {@value Permissions#REPORT} is granted to everybody unless a permission plugin explicitly denies it.
 * The command adds no cooldown: the backend's report limiter is the only limit and its answer is shown
 * as a "please wait" message.
 */
public final class ReportCommand {

    private final ReportService service;

    @Inject
    public ReportCommand(@NotNull ReportService service) {
        this.service = service;
    }

    @Command(value = "report <user> <reason> [details]", requiredSender = Player.class)
    @CommandDescription("Report a player to the team")
    public void report(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument(value = "reason", suggestions = "report-reasons") @NotNull String reason,
            @Argument(value = "details") @Greedy @Nullable String details
    ) {
        handle(context.sender(), user, reason, details);
    }

    @Suggestions("report-reasons")
    public List<String> suggestReasons(CommandContext<CommandSource> context, CommandInput input) {
        return ReportCategories.suggest(input.readString());
    }

    /** The behaviour of the command without the command framework, for tests. */
    void handle(@NotNull Player sender, @NotNull ResolvedTarget user, @NotNull String reason, @Nullable String details) {
        if (sender.getPermissionValue(Permissions.REPORT) == Tristate.FALSE) {
            sender.sendMessage(ReportMessages.denied());
            return;
        }
        Optional<ReportCategory> category = ReportCategories.parse(reason);
        if (category.isEmpty()) {
            sender.sendMessage(ReportMessages.unknownCategory(reason));
            return;
        }
        Player online = user.onlinePlayer();
        boolean bypass = online != null && online.hasPermission(Permissions.REPORT_BYPASS);
        ReportService.Outcome outcome = this.service.submit(sender.getUniqueId(), user.uuid(), bypass, category.get(), details);
        sender.sendMessage(ReportMessages.outcome(outcome, user.name(), category.get()));
    }
}
