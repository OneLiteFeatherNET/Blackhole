package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.component.PunishProfileComponents;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PunishInfoCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishInfoCommand.class);

    private final BackendGateway backend;

    @Inject
    public PunishInfoCommand(@NotNull BackendGateway backend) {
        this.backend = backend;
    }

    @Command("blackhole <user> info")
    @Permission("blackhole.user.info")
    @CommandDescription("Get information about a player's active punishments")
    public void userInfo(
            @NotNull CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user
    ) {
        PunishProfileDTO profile = load(context.sender(), user);
        if (profile != null) {
            context.sender().sendMessage(PunishProfileComponents.componentRepresentation(user.name(), profile));
        }
    }

    @Command("blackhole <user> history <sort>")
    @Permission("blackhole.ban.history")
    @CommandDescription("Get the punishment history of a player")
    public void banHistory(
            @NotNull CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument("sort") String sort
    ) {
        PunishProfileDTO profile = load(context.sender(), user);
        if (profile != null) {
            context.sender().sendMessage(PunishProfileComponents.historyRepresentation(user.name(), profile));
        }
    }

    /**
     * @return the player's profile, an empty one if they have none yet, or {@code null} after telling
     * the sender the backend could not be reached
     */
    private PunishProfileDTO load(Player sender, ResolvedTarget user) {
        try {
            return this.backend.profile(user.ownerHash()).orElseGet(() -> new PunishProfileDTO().owner(user.ownerHash()));
        } catch (ApiException e) {
            LOGGER.error("Failed to fetch the punishment profile of {}: {}", user.name(), e.getMessage());
            sender.sendMessage(Component.translatable("punishment.error.profile"));
            return null;
        }
    }
}
