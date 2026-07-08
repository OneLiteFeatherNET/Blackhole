package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.api.PunishmentApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.parser.Parser;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.key.CloudKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Reverses an active ban/mute. Unlike {@link PunishCommand}'s {@code profile} parser, a pardon
 * target is typically offline (that's the whole point of unbanning them), so this command needs
 * its own parser that doesn't require a live {@link Player} - see {@link #parseProfile}.
 */
public final class PardonCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(PardonCommand.class);
    private static final CloudKey<String> DISPLAY_NAME_KEY = CloudKey.of("pardonDisplayName", String.class);

    private final ProxyServer proxyServer;
    private final PunishProfileApi punishProfileApi;
    private final PunishmentApi punishmentApi;
    private final BlackholeConfig config;

    @Inject
    public PardonCommand(@NotNull ProxyServer proxyServer, @NotNull ApiClient apiClient, @NotNull BlackholeConfig config) {
        this.proxyServer = proxyServer;
        this.punishProfileApi = new PunishProfileApi(apiClient);
        this.punishmentApi = new PunishmentApi(apiClient);
        this.config = config;
    }

    @Command("blackhole <user> unban")
    @Command("unban <user>")
    @Permission("blackhole.unban")
    @CommandDescription("Revoke a player's active ban")
    public void unbanPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "pardon-profile") @NotNull PunishProfileDTO user
    ) {
        if (user.getActiveBan() == null) {
            context.sender().sendMessage(Component.translatable("punishment.error.unban.none_active"));
            return;
        }
        try {
            this.punishmentApi.revokeBan(this.config.getTenantId(), user.getOwner(), context.sender().getUniqueId());
        } catch (ApiException e) {
            LOGGER.error("Failed to unban player {}: {}", user.getOwner(), e.getMessage());
            context.sender().sendMessage(Component.translatable("punishment.error.unban"));
            return;
        }
        context.sender().sendMessage(Component.translatable("punishment.success.unban").arguments(Component.text(context.get(DISPLAY_NAME_KEY))));
    }

    @Command("blackhole <user> unmute")
    @Command("unmute <user>")
    @Permission("blackhole.unmute")
    @CommandDescription("Revoke a player's active mute")
    public void unmutePlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "pardon-profile") @NotNull PunishProfileDTO user
    ) {
        if (user.getActiveChatBan() == null) {
            context.sender().sendMessage(Component.translatable("punishment.error.unmute.none_active"));
            return;
        }
        try {
            this.punishmentApi.revokeMute(this.config.getTenantId(), user.getOwner(), context.sender().getUniqueId());
        } catch (ApiException e) {
            LOGGER.error("Failed to unmute player {}: {}", user.getOwner(), e.getMessage());
            context.sender().sendMessage(Component.translatable("punishment.error.unmute"));
            return;
        }
        context.sender().sendMessage(Component.translatable("punishment.success.unmute").arguments(Component.text(context.get(DISPLAY_NAME_KEY))));
    }

    @Parser(name = "pardon-profile")
    public PunishProfileDTO parseProfile(CommandContext<CommandSource> context, CommandInput input) {
        String name = input.readString();
        context.store(DISPLAY_NAME_KEY, name);

        String ownerHash = this.proxyServer.getPlayer(name)
                .map(Player::getUniqueId)
                .map(UUIDConverter::convertToSHA)
                .orElseGet(() -> offlineOwnerHash(name));

        try {
            return this.punishProfileApi.getById(this.config.getTenantId(), ownerHash);
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch punish profile for {}: {}", name, e.getMessage());
            throw new IllegalArgumentException("No punishment profile found for %s".formatted(name));
        }
    }

    private static String offlineOwnerHash(String name) {
        try {
            return UUIDConverter.convertToSHA(UUID.fromString(name));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Player(%s) is not online and is not a valid UUID".formatted(name));
        }
    }
}
