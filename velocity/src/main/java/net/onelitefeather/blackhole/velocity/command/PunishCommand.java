package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.blackhole.api.template.PunishTemplate;
import net.onelitefeather.blackhole.api.utils.UUIDConverter;
import net.onelitefeather.blackhole.web.BlackholeClient;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.annotations.parser.Parser;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public final class PunishCommand {

    private final ProxyServer proxyServer;
    private final BlackholeClient blackholeClient;

    @Inject
    public PunishCommand(@NotNull ProxyServer proxyServer, @NotNull BlackholeClient blackholeClient) {
        this.proxyServer = proxyServer;
        this.blackholeClient = blackholeClient;
    }

    @Command("blackhole <user> ban <template> [type]")
    @Permission("blackhole.ban")
    @CommandDescription("Ban a player from the server")
    public void banPlayer(
            @NotNull Player sender,
            @Argument("target") @NotNull String target,
            @Argument(value = "template") @NotNull PunishTemplate template,
            @Argument("type") @Default(value = "NETWORK") PunishType type
    ) {
        Player targetPlayer = this.proxyServer.getPlayer(target).orElse(null);

        if (targetPlayer == null) {
            sender.sendMessage(Component.text("The player is not online."));
            return;
        }

        String targetHash = UUIDConverter.convertToSHA(targetPlayer.getUniqueId());
        Optional<PunishProfile> profileOptional = this.blackholeClient.profileRequests().get(targetHash);

        PunishProfile punishProfile = null;

        if (profileOptional.isEmpty()) {
            String playerHash = UUIDConverter.convertToSHA(targetPlayer.getUniqueId());
            punishProfile = this.blackholeClient.profileRequests().add(PunishProfile.builder().owner(playerHash).build());
        } else {
            punishProfile = profileOptional.get();
        }

        if (punishProfile == null) {
            sender.sendMessage(Component.text("An error occurred while trying to ban the player."));
            return;
        }

        this.blackholeClient.punishRequests().add(punishProfile.owner(), template.identifier(), sender.getUniqueId());

        targetPlayer.disconnect(Component.text("You have been banned from the server."));
        sender.sendMessage(Component.text("The player has been banned."));

        // Ban the player
    }

    @Command("blackhole <user> mute <template>")
    @Permission("blackhole.mute")
    @CommandDescription("Mute a player on the server")
    public void mutePlayer(
            @NotNull CommandSource sender,
            @Argument("target") @NotNull String target,
            @Argument("template") @NotNull PunishTemplate template
    ) {
        // Mute the player
    }

    @Parser(suggestions = "templates")
    public PunishTemplate parseTemplate(CommandContext<CommandSource> context, CommandInput input) {
        String name = input.readString();
        List<PunishTemplate> templates = this.blackholeClient.templateRequests().getAll();

        if (templates.isEmpty()) {
            context.sender().sendMessage(Component.text("No templates found."));
            return null;
        }

        return templates.stream().filter(template -> template.reason().equals(name)).findFirst().orElse(null);
    }

    @Parser(suggestions = "templates")
    public List<String> suggestions(CommandContext<Player> context, CommandInput input) {
        return this.blackholeClient.templateRequests().getAll().stream().map(PunishTemplate::reason).toList();
    }
}
