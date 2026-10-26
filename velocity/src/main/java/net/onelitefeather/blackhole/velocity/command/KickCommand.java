package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.KickAccepted;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.velocity.component.KickMessages;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
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
 * {@code /kick <player> [server|section] <reason>}: takes one player off the network, a server or a
 * section. The backend call and the wait for the proxies' answers run on the async pool and a delay (see
 * {@link KickRunner}); the command itself returns at once and the result is sent to the staff member when
 * it is known.
 */
public final class KickCommand {

    private final KickRunner runner;
    private final ProxyServer proxy;
    private final SectionRegistry sections;

    @Inject
    public KickCommand(@NotNull KickRunner runner, @NotNull ProxyServer proxy, @NotNull SectionRegistry sections) {
        this.runner = runner;
        this.proxy = proxy;
        this.sections = sections;
    }

    @Command(value = "blackhole <user> kick <details>", requiredSender = Player.class)
    @Command(value = "kick <user> <details>", requiredSender = Player.class)
    @Permission(Permissions.KICK)
    @CommandDescription("Kick a player off the network, a server or a section")
    public void kick(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument(value = "details", suggestions = "kickDetails") @Greedy @NotNull String details
    ) {
        handle(context.sender(), user, details);
    }

    /** Completes the keyword and then the server names or section keys; the reason is free text. */
    @Suggestions("kickDetails")
    public List<String> suggestDetails(CommandContext<CommandSource> context, CommandInput input) {
        String typed = input.remainingInput();
        String[] words = typed.split("\\s+", -1);
        if (words.length == 1) {
            return Stream.of("server", "section").filter(word -> word.startsWith(words[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (words.length == 2) {
            Stream<String> names = switch (words[0].toLowerCase(Locale.ROOT)) {
                case "server" -> this.proxy.getAllServers().stream().map(RegisteredServer::getServerInfo).map(info -> info.getName());
                case "section" -> this.sections.all().stream().map(SectionInfo::key);
                default -> Stream.empty();
            };
            return names.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(words[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    /** The behaviour of the command without the command framework, for tests. */
    void handle(@NotNull Player sender, @NotNull ResolvedTarget user, @NotNull String details) {
        KickArguments.Result parsed = KickArguments.parse(details);
        if (parsed instanceof KickArguments.Invalid invalid) {
            sender.sendMessage(KickMessages.invalid(invalid.message()));
            return;
        }
        KickArguments.Parsed kick = (KickArguments.Parsed) parsed;
        String where = kick.target() == null ? null : KickMessages.subjectOf(kick.scope(), kick.target());
        this.runner.kickPlayer(user.ownerHash(), kick.scope(), kick.target(), kick.reason(), new KickRunner.Listener() {
            @Override
            public void accepted(@NotNull KickAccepted accepted) {
                sender.sendMessage(KickMessages.sent(user.name()));
                for (String warning : accepted.getWarnings()) {
                    sender.sendMessage(KickMessages.invalid(warning));
                }
            }

            @Override
            public void settled(@NotNull KickResponse response) {
                sender.sendMessage(KickMessages.outcome(response, user.name(), where, false));
            }

            @Override
            public void timedOut(@NotNull String id) {
                sender.sendMessage(KickMessages.timedOut(user.name()));
            }

            @Override
            public void failed(@NotNull ApiException cause) {
                sender.sendMessage(KickMessages.failed(cause.getCode()));
            }
        });
    }
}
