package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.api.PlayerApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.api.PunishmentApi;
import net.onelitefeather.blackhole.client.api.PunishmentTemplatesApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.Pageable;
import net.onelitefeather.blackhole.client.model.PlayerResolveResponse;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateDTO;
import net.onelitefeather.blackhole.client.model.PunishType;
import net.onelitefeather.blackhole.velocity.BlackholeVelocity;
import net.onelitefeather.blackhole.velocity.component.PunishmentTemplateComponent;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.annotations.parser.Parser;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.key.CloudKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PunishCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishCommand.class);
    static final CloudKey<ResolvedTarget> TARGET_KEY = CloudKey.of("target", ResolvedTarget.class);

    /**
     * A punishment target resolved either from a currently-connected {@link Player} or, if they
     * aren't online, from the backend's player-resolver chain (Otis/Mojang/NameMC) - see
     * {@link #parseProfile}. {@code onlinePlayer} is {@code null} for an offline target; callers
     * must not disconnect/message a player directly in that case.
     */
    public record ResolvedTarget(UUID uuid, String name, @Nullable Player onlinePlayer) {
    }

    private final ProxyServer proxyServer;
    private final PunishProfileApi punishProfileApi;
    private final PunishmentApi punishmentApi;
    private final PunishmentTemplatesApi punishmentTemplatesApi;
    private final PlayerApi playerApi;

    @Inject
    public PunishCommand(@NotNull ProxyServer proxyServer, @NotNull ApiClient apiClient) {
        this.proxyServer = proxyServer;
        this.punishProfileApi = new PunishProfileApi(apiClient);
        this.punishmentApi = new PunishmentApi(apiClient);
        this.punishmentTemplatesApi = new PunishmentTemplatesApi(apiClient);
        this.playerApi = new PlayerApi(apiClient);
    }

    @Command("blackhole <user> ban <template>")
    @Command("ban <user> <template>")
    @Permission("blackhole.ban")
    @CommandDescription("Ban a player from the server")
    public void banPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "profile", suggestions = "players") @NotNull PunishProfileDTO user,
            @Argument(value = "template", parserName = "template") @NotNull PunishTemplateDTO template,
            @Flag("server") Boolean server
    ) {
        if (server != null && template.getType() != PunishType.SERVER) {
            context.sender().sendMessage(Component.translatable("punishment.error.template.only_server"));
            return;
        }

        if (template.getIdentifier() == null) {
            context.sender().sendMessage(Component.translatable("punishment.error.template.not_found").arguments(Component.text(template.getIdentifier().toString())));
            return;
        }

        Optional<PunishProfileDTO> profile = Optional.empty();

        try {
            profile = Optional.ofNullable(this.punishmentApi.addActivePunishment(
                    user.getOwner(),
                    template.getIdentifier(),
                    context.sender().getUniqueId()
            )).map(PunishProfileDTO.class::cast);
        } catch (ApiException e) {
            LOGGER.error("Failed to ban player {}: {}", user.getOwner(), e.getMessage());
            context.sender().sendMessage(Component.translatable("punishment.error.ban"));
            return;
        }

        ResolvedTarget target = context.get(TARGET_KEY);
        PunishProfileDTO updatedProfile = profile.orElseThrow();
        if (target.onlinePlayer() != null) {
            target.onlinePlayer().disconnect(PunishmentTemplateComponent.of(template, updatedProfile));
        }
        context.sender().sendMessage(Component.translatable("punishment.success.ban").arguments(Component.text(target.name())));
    }

    @Command("blackhole <user> mute <template>")
    @Command("mute <user> <template>")
    @Permission("blackhole.mute")
    @CommandDescription("Mute a player on the server")
    @PunishTypeScope(PunishType.CHAT)
    public void mutePlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "profile", suggestions = "players") @NotNull PunishProfileDTO user,
            @Argument(value = "template", parserName = "template") @NotNull PunishTemplateDTO template
    ) {
        if (template.getIdentifier() == null) {
            context.sender().sendMessage(Component.translatable("punishment.error.template.not_found").arguments(Component.text(template.getIdentifier().toString())));
            return;
        }
        Optional<PunishProfileDTO> profile = Optional.empty();
        try {
            profile = Optional.ofNullable(
                    this.punishmentApi.addActivePunishment(
                            user.getOwner(),
                            template.getIdentifier(),
                            context.sender().getUniqueId()
                    )
            ).map(PunishProfileDTO.class::cast);
        } catch (ApiException e) {
            LOGGER.error("Failed to mute player {}: {}", user.getOwner(), e.getMessage());
            context.sender().sendMessage(Component.translatable("punishment.error.mute"));
            return;
        }
        PunishProfileDTO updatedProfile = profile.orElseThrow();

        context.sender().sendMessage(PunishmentTemplateComponent.of(template, updatedProfile));
    }

    @Parser(name = "profile")
    public PunishProfileDTO parseProfile(CommandContext<CommandSource> context, CommandInput input) {
        String name = input.readString();
        Optional<Player> onlinePlayer = this.proxyServer.getPlayer(name);

        UUID uuid;
        String resolvedName;
        if (onlinePlayer.isPresent()) {
            uuid = onlinePlayer.get().getUniqueId();
            resolvedName = onlinePlayer.get().getUsername();
        } else {
            // Not connected right now - fall back to the backend's resolver chain (Otis/Mojang/
            // NameMC) so offline players can still be targeted; enforcement itself is already
            // UUID-driven at login (PlayerLoginListener), so nothing further is needed here.
            PlayerResolveResponse resolved;
            try {
                resolved = this.playerApi.resolvePlayer(name);
            } catch (ApiException e) {
                throw new IllegalArgumentException("Player(%s) not found".formatted(name));
            }
            uuid = resolved.getUuid();
            resolvedName = resolved.getName();
        }
        context.store(TARGET_KEY, new ResolvedTarget(uuid, resolvedName, onlinePlayer.orElse(null)));

        try {
            return this.punishProfileApi.getById(UUIDConverter.convertToSHA(uuid));
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch punish profile for player {}: {}", name, e.getMessage());
            return null;
        }
    }

    @Suggestions("players")
    public List<String> suggestPlayers(CommandContext<Player> context, CommandInput input) {
        String prefix = input.readString();
        return this.proxyServer.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(username -> username.toLowerCase().startsWith(prefix.toLowerCase()))
                .toList();
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
