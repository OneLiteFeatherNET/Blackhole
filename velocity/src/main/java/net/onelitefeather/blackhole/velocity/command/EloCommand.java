package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.velocity.component.Mode;
import net.onelitefeather.blackhole.velocity.component.ProfileMessages;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.punish.EloReport;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * {@code /elo <user>}: the player's score in every section with the section's thresholds and
 * whether a permanent punishment is active there.
 */
public final class EloCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(EloCommand.class);

    private final BackendGateway backend;

    @Inject
    public EloCommand(@NotNull BackendGateway backend) {
        this.backend = backend;
    }

    @Command("blackhole <user> elo")
    @Command("elo <user>")
    @Permission("blackhole.elo.view")
    @CommandDescription("Show a player's ELO scores per section")
    public void showElo(
            CommandContext<CommandSource> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user
    ) {
        EloProfileResponse elo;
        try {
            elo = this.backend.elo(user.ownerHash());
        } catch (ApiException e) {
            LOGGER.error("Failed to load the ELO of {}: {}", user.name(), e.getMessage());
            context.sender().sendMessage(Component.translatable("elo.error"));
            return;
        }
        Optional<net.onelitefeather.blackhole.client.model.PunishProfileDTO> profile = Optional.empty();
        try {
            profile = this.backend.profile(user.ownerHash());
        } catch (ApiException e) {
            LOGGER.debug("Profile of {} unavailable for the ELO view: {}", user.name(), e.getMessage());
        }

        context.sender().sendMessage(ProfileMessages.elo(user.name(), EloReport.rows(elo, profile), Mode.of(context.sender())));
    }
}
