package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.api.PunishmentApi;
import net.onelitefeather.blackhole.client.api.PunishmentTemplatesApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.Pageable;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateDTO;
import net.onelitefeather.blackhole.client.model.PunishType;
import net.onelitefeather.blackhole.velocity.BlackholeVelocity;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.annotations.parser.Parser;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.key.CloudKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public final class PunishCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishCommand.class);
    static final CloudKey<Player> TARGET_KEY = CloudKey.of("target", Player.class);

    private final ProxyServer proxyServer;
    private final PunishProfileApi punishProfileApi;
    private final PunishmentApi punishmentApi;
    private final PunishmentTemplatesApi punishmentTemplatesApi;

    @Inject
    public PunishCommand(@NotNull ProxyServer proxyServer, @NotNull ApiClient apiClient) {
        this.proxyServer = proxyServer;
        this.punishProfileApi = new PunishProfileApi(apiClient);
        this.punishmentApi = new PunishmentApi(apiClient);
        this.punishmentTemplatesApi = new PunishmentTemplatesApi(apiClient);
    }

    @Command("blackhole <user> ban <template>")
    @Command("ban <user> <template>")
    @Permission("blackhole.ban")
    @CommandDescription("Ban a player from the server")
    public void banPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "profile") @NotNull PunishProfileDTO user,
            @Argument(value = "template", parserName = "template") @NotNull PunishTemplateDTO template,
            @Flag("server") Boolean server
    ) {
        if (server != null && template.getType() != PunishType.SERVER) {
            context.sender().sendMessage(Component.text("You can only ban a player from the server with a server template."));
            return;
        }

        if (template.getIdentifier() == null) {
            context.sender().sendMessage(Component.text("The specified template does not exist."));
            return;
        }

        try {
            this.punishmentApi.addActivePunishment(
                    user.getOwner(),
                    template.getIdentifier(),
                    context.sender().getUniqueId()
            );
        } catch (ApiException e) {
            LOGGER.error("Failed to ban player {}: {}", user.getOwner(), e.getMessage());
            context.sender().sendMessage(Component.text("An error occurred while trying to ban the player."));
            return;
        }

        Player targetPlayer = context.get(TARGET_KEY);
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
            @Argument(value = "user", parserName = "profile") @NotNull PunishProfileDTO user,
            @Argument(value = "template", parserName = "template") @NotNull PunishTemplateDTO template
    ) {
        if (template.getIdentifier() == null) {
            context.sender().sendMessage(Component.text("The specified template does not exist."));
            return;
        }
        try {
            this.punishmentApi.addActivePunishment(
                    user.getOwner(),
                    template.getIdentifier(),
                    context.sender().getUniqueId()
            );
        } catch (ApiException e) {
            LOGGER.error("Failed to mute player {}: {}", user.getOwner(), e.getMessage());
            context.sender().sendMessage(Component.text("An error occurred while trying to mute the player."));
            return;
        }

        context.sender().sendMessage(Component.text("The player has been muted."));
    }

    @Parser(name = "profile")
    public PunishProfileDTO parseProfile(CommandContext<CommandSource> context, CommandInput input) {
        String name = input.readString();
        Optional<Player> player = this.proxyServer.getPlayer(name);

        if (player.isEmpty()) {
            throw new IllegalArgumentException("Player(%s) not found".formatted(name));
        }
        context.store(TARGET_KEY, player.get());

        try {
            return this.punishProfileApi.getById(UUIDConverter.convertToSHA(player.get().getUniqueId()));
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch punish profile for player {}: {}", name, e.getMessage());
            return null;
        }
    }

    @Parser(suggestions = "templates", name = "template")
    public PunishTemplateDTO parseTemplate(CommandContext<CommandSource> context, CommandInput input) {
        String name = input.readString();
        boolean server = context.flags().isPresent("server");
        List<PunishTemplateDTO> templates = List.of();
        try {
            templates = this.punishmentTemplatesApi.getAllTemplates(Pageable.builder().build());
        } catch (ApiException e) {
            throw new RuntimeException(e);
        }
        templates.removeIf(template -> server && template.getType() != PunishType.SERVER);
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("No templates found");
        }

        return templates.stream().filter(template -> template.getReason().equals(name)).findFirst().orElse(null);
    }

    @Suggestions(value = "templates")
    public List<String> suggestions(CommandContext<Player> context, CommandInput input) {
        String s = input.readString();
        if (context.contains(BlackholeVelocity.PUNISH_TYPE_KEY)) {
            PunishType type = context.get(BlackholeVelocity.PUNISH_TYPE_KEY);
            try {
                return this.punishmentTemplatesApi.getAllTemplates(Pageable.builder().build())
                        .stream()
                        .filter(template -> template.getType() == type)
                        .map(PunishTemplateDTO::getReason)
                        .filter(reason -> reason.toLowerCase().contains(s.toLowerCase()))
                        .toList();
            } catch (ApiException e) {
                LOGGER.error("Failed to fetch templates for suggestions: {}", e.getMessage());
                return List.of();
            }
        }
        try {
            return this.punishmentTemplatesApi.getAllTemplates(Pageable.builder().build())
                    .stream()
                    .map(PunishTemplateDTO::getReason)
                    .filter(reason -> reason.toLowerCase().contains(s.toLowerCase()))
                    .toList();
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch templates for suggestions: {}", e.getMessage());
            return List.of();
        }
    }
}
