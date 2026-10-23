package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.component.Mode;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncHandler;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Default;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Lifts an active ban or mute. The target may be offline, so it is resolved like any other punishment
 * target. Lifting a permanent punishment additionally needs {@value Permissions#PARDON_PERMANENT}.
 */
public final class PardonCommand {

    private final PunishService service;
    private final PunishmentSyncHandler syncHandler;
    private final SectionRegistry sections;

    @Inject
    public PardonCommand(@NotNull PunishService service, @NotNull PunishmentSyncHandler syncHandler,
                         @NotNull SectionRegistry sections) {
        this.service = service;
        this.syncHandler = syncHandler;
        this.sections = sections;
    }

    @Command("blackhole <user> unban [section]")
    @Command("unban <user> [section]")
    @Permission(Permissions.UNBAN)
    @CommandDescription("Revoke a player's active ban (default section: network)")
    public void unbanPlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument("section") @Default("network") @NotNull String section
    ) {
        revoke(context.sender(), user, section, PunishService.BAN_KINDS, "unban");
    }

    @Command("blackhole <user> unmute [section]")
    @Command("unmute <user> [section]")
    @Permission(Permissions.UNMUTE)
    @CommandDescription("Revoke a player's active mute (default section: chat)")
    public void unmutePlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument("section") @Default("chat") @NotNull String section
    ) {
        revoke(context.sender(), user, section, PunishService.MUTE_KINDS, "unmute");
    }

    private void revoke(Player sender, ResolvedTarget user, String section, Set<EnforcementKind> kinds, String action) {
        boolean mayPardonPermanent = Permissions.of(sender).mayPardonPermanent();
        PunishService.RevokeOutcome outcome = this.service.revoke(user.ownerHash(), section, sender.getUniqueId(), mayPardonPermanent, kinds);
        if (outcome instanceof PunishService.RevokeOutcome.Revoked revoked) {
            // Keeps this proxy's mirror right even if the Redis announcement is not delivered.
            this.syncHandler.accept(new PunishmentSyncMessage(
                    user.ownerHash(), section, null, PunishmentSyncMessage.STATE_CLEARED,
                    revoked.response().getPunishment().getIdentifier(), null, null, null));
        }
        sender.sendMessage(StaffMessages.revoked(outcome, user.name(), action, section,
                this.sections.ofKind(kinds.toArray(EnforcementKind[]::new)), Mode.of(sender)));
    }
}
