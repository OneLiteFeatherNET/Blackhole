package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.onelitefeather.blackhole.client.api.PlayerApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.PlayerResolveResponse;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.incendo.cloud.annotations.parser.Parser;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * The shared player argument of the staff commands ({@code target}) and its tab completion
 * ({@code players}). Offline players are resolved through the backend's player resolver.
 */
public final class TargetParser {

    private final ProxyServer proxyServer;
    private final TargetResolver resolver;

    @Inject
    public TargetParser(@NotNull ProxyServer proxyServer, @NotNull ApiClient apiClient) {
        this.proxyServer = proxyServer;
        PlayerApi playerApi = new PlayerApi(apiClient);
        this.resolver = new TargetResolver(proxyServer::getPlayer, name -> {
            PlayerResolveResponse resolved = playerApi.resolvePlayer(name);
            return Optional.of(new TargetResolver.Known(resolved.getUuid(), resolved.getName()));
        });
    }

    @Parser(name = "target")
    public ResolvedTarget parseTarget(CommandContext<CommandSource> context, CommandInput input) {
        return this.resolver.resolve(input.readString());
    }

    @Suggestions("players")
    public List<String> suggestPlayers(CommandContext<Player> context, CommandInput input) {
        String prefix = input.readString().toLowerCase();
        return this.proxyServer.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(username -> username.toLowerCase().startsWith(prefix))
                .toList();
    }
}
