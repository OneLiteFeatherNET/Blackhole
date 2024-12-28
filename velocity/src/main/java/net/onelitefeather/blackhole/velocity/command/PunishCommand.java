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
import net.onelitefeather.blackhole.velocity.BlackholeVelocity;
import net.onelitefeather.blackhole.web.BlackholeClient;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.parser.Parser;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.key.CloudKey;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public final class PunishCommand {

    static final CloudKey<Player> TARGET_KEY = CloudKey.of("target", Player.class);

    private final ProxyServer proxyServer;
    private final BlackholeClient blackholeClient;

    @Inject
    public PunishCommand(@NotNull ProxyServer proxyServer, @NotNull BlackholeClient blackholeClient) {
        this.proxyServer = proxyServer;
        this.blackholeClient = blackholeClient;
    }

    @Command("blackhole <user> ban <template>")
    @Command("ban <user> <template>")
    @Permission("blackhole.ban")
    @CommandDescription("Ban a player from the server")
    public void banPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "profile") @NotNull PunishProfile user,
            @Argument(value = "template", parserName = "template") @NotNull PunishTemplate template,
            @Flag("server") Boolean server
    ) {
        if (server != null && template.type() != PunishType.SERVER) {
            context.sender().sendMessage(Component.text("You can only ban a player from the server with a server template."));
            return;
        }
        Player targetPlayer = context.get(TARGET_KEY);
        this.blackholeClient.punishRequests().add(user.owner(), template.identifier(), context.sender().getUniqueId());

        targetPlayer.disconnect(Component.text("You have been banned from the server."));
        context.sender().sendMessage(Component.text("The player has been banned."));
    }

    @Command("blackhole <user> mute <template>")
    @Command("mute <user> <template>")
    @Permission("blackhole.mute")
    @CommandDescription("Mute a player on the server")
    @PunishTypeScope(PunishType.CHAT)
    public void mutePlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "profile") @NotNull PunishProfile user,
            @Argument(value = "template", parserName = "template") @NotNull PunishTemplate template
    ) {
        Player targetPlayer = context.get(TARGET_KEY);
        this.blackholeClient.punishRequests().add(user.owner(), template.identifier(), context.sender().getUniqueId());

        targetPlayer.disconnect(Component.text("You have been banned from the server."));
        context.sender().sendMessage(Component.text("The player has been banned."));
    }

    @Parser(name = "profile")
    public PunishProfile parseProfile(CommandContext<CommandSource> context, CommandInput input) {
        String name = input.readString();
        Optional<Player> player = this.proxyServer.getPlayer(name);

        if (player.isEmpty()) {
            throw new IllegalArgumentException("Player(%s) not found".formatted(name));
        }
        context.store(TARGET_KEY, player.get());

        return this.blackholeClient.profileRequests().get(UUIDConverter.convertToSHA(player.get().getUniqueId())).orElse(null);
    }

    @Parser(suggestions = "templates", name = "template")
    public PunishTemplate parseTemplate(CommandContext<CommandSource> context, CommandInput input) {
        String name = input.readString();
        boolean server = context.flags().isPresent("server");
        List<PunishTemplate> templates = this.blackholeClient.templateRequests().getAll();
        templates.removeIf(template -> server && template.type() != PunishType.SERVER);
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("No templates found");
        }

        return templates.stream().filter(template -> template.reason().equals(name)).findFirst().orElse(null);
    }

    @Suggestions(value = "templates")
    public List<String> suggestions(CommandContext<Player> context, CommandInput input) {
        String s = input.readString();
        if (context.contains(BlackholeVelocity.PUNISH_TYPE_KEY)) {
            PunishType type = context.get(BlackholeVelocity.PUNISH_TYPE_KEY);
            return this.blackholeClient.templateRequests()
                    .getAll()
                    .stream()
                    .filter(template -> template.type() == type)
                    .map(PunishTemplate::reason)
                    .filter(reason -> reason.toLowerCase().contains(s.toLowerCase()))
                    .toList();
        }
        return this.blackholeClient.templateRequests()
                .getAll()
                .stream()
                .filter(PunishTemplate::commandable)
                .map(PunishTemplate::command)
                .filter(command -> command.toLowerCase().contains(s.toLowerCase()))
                .toList();
    }
}
