package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncHandler;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
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
 * target. Lifting a permanent punishment additionally needs {@value #PARDON_PERMANENT_PERMISSION}.
 */
public final class PardonCommand {

    static final String PARDON_PERMANENT_PERMISSION = "blackhole.pardon.permanent";

    private final PunishService service;
    private final PunishmentSyncHandler syncHandler;

    @Inject
    public PardonCommand(@NotNull PunishService service, @NotNull PunishmentSyncHandler syncHandler) {
        this.service = service;
        this.syncHandler = syncHandler;
    }

    @Command("blackhole <user> unban [section]")
    @Command("unban <user> [section]")
    @Permission("blackhole.unban")
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
    @Permission("blackhole.unmute")
    @CommandDescription("Revoke a player's active mute (default section: chat)")
    public void unmutePlayer(
            CommandContext<Player> context,
            @Argument(value = "user", parserName = "target", suggestions = "players") @NotNull ResolvedTarget user,
            @Argument("section") @Default("chat") @NotNull String section
    ) {
        revoke(context.sender(), user, section, PunishService.MUTE_KINDS, "unmute");
    }

    private void revoke(Player sender, ResolvedTarget user, String section, Set<EnforcementKind> kinds, String action) {
        boolean mayPardonPermanent = sender.hasPermission(PARDON_PERMANENT_PERMISSION);
        PunishService.RevokeOutcome outcome = this.service.revoke(user.ownerHash(), section, sender.getUniqueId(), mayPardonPermanent, kinds);
        switch (outcome) {
            case PunishService.RevokeOutcome.Revoked revoked -> {
                // Keeps this proxy's mirror right even if the Redis announcement is not delivered.
                this.syncHandler.accept(new PunishmentSyncMessage(
                        user.ownerHash(), section, null, PunishmentSyncMessage.STATE_CLEARED,
                        revoked.response().getPunishment().getIdentifier(), null, null, null));
                sender.sendMessage(Component.translatable("punishment.success." + action).arguments(Component.text(user.name())));
            }
            case PunishService.RevokeOutcome.NothingActive ignored ->
                    sender.sendMessage(Component.translatable("punishment.error." + action + ".none_active"));
            case PunishService.RevokeOutcome.PermanentNotAllowed ignored ->
                    sender.sendMessage(Component.translatable("punishment.error.pardon.permanent_permission"));
            case PunishService.RevokeOutcome.UnknownSection unknown ->
                    sender.sendMessage(Component.translatable("punishment.error.section.unknown").arguments(Component.text(unknown.section())));
            case PunishService.RevokeOutcome.WrongSection wrong ->
                    sender.sendMessage(Component.translatable("punishment.error.section.wrong_kind").arguments(Component.text(wrong.section())));
            case PunishService.RevokeOutcome.Failed ignored -> sender.sendMessage(Component.translatable("punishment.error." + action));
        }
    }
}
