package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.KickAccepted;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.velocity.component.KickMessages;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code /kickall <server|section> <reason>}: takes everybody off one server or section, as one audited
 * action. A bare name is a section when a section with that key exists, otherwise a server; the prefixes
 * {@code server:} and {@code section:} decide explicitly. Needs {@value Permissions#KICK_MASS}.
 */
public final class KickAllCommand {

    private final KickRunner runner;
    private final ProxyServer proxy;
    private final SectionRegistry sections;

    @Inject
    public KickAllCommand(@NotNull KickRunner runner, @NotNull ProxyServer proxy, @NotNull SectionRegistry sections) {
        this.runner = runner;
        this.proxy = proxy;
        this.sections = sections;
    }

    @Command(value = "kickall <target> <reason>", requiredSender = Player.class)
    @Permission(Permissions.KICK_MASS)
    @CommandDescription("Kick everybody off a server or a section")
    public void kickAll(
            CommandContext<Player> context,
            @Argument(value = "target", suggestions = "kickTargets") @NotNull String target,
            @Argument("reason") @Greedy @NotNull String reason
    ) {
        handle(context.sender(), target, reason);
    }

    @Suggestions("kickTargets")
    public List<String> suggestTargets(CommandContext<CommandSource> context, CommandInput input) {
        String prefix = input.readString().toLowerCase(Locale.ROOT);
        return Stream.concat(
                        this.sections.all().stream().map(SectionInfo::key),
                        this.proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()))
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .toList();
    }

    /** The behaviour of the command without the command framework, for tests. */
    void handle(@NotNull Player sender, @NotNull String target, @NotNull String reason) {
        KickArguments.Result parsed = KickArguments.parseMass(target, reason, key -> this.sections.find(key).isPresent());
        if (parsed instanceof KickArguments.Invalid invalid) {
            sender.sendMessage(KickMessages.invalid(invalid.message()));
            return;
        }
        KickArguments.Parsed kick = (KickArguments.Parsed) parsed;
        String subject = KickMessages.subjectOf(kick.scope(), kick.target());
        this.runner.kickMass(kick.scope(), kick.target(), kick.reason(), new KickRunner.Listener() {
            @Override
            public void accepted(@NotNull KickAccepted accepted) {
                sender.sendMessage(KickMessages.sent(subject));
            }

            @Override
            public void settled(@NotNull KickResponse response) {
                sender.sendMessage(KickMessages.outcome(response, subject, subject, true));
            }

            @Override
            public void timedOut(@NotNull String id) {
                sender.sendMessage(KickMessages.timedOut(subject));
            }

            @Override
            public void failed(@NotNull ApiException cause) {
                sender.sendMessage(KickMessages.failed(cause.getCode()));
            }
        });
    }
}
