package net.onelitefeather.blackhole.velocity.guard;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.AreaBanCreateRequest;
import net.onelitefeather.blackhole.client.model.AreaBanResponse;
import net.onelitefeather.blackhole.velocity.component.GuardMessages;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /areaban asn|country ...} bans an area and {@code /areaban lift asn|country <value> <reason>} lifts
 * the active ban of it. The reading is in {@link AreaBanArguments}; this class makes at most two backend calls.
 */
public final class AreaBanCommand {

    private final GuardBackend backend;

    @Inject
    public AreaBanCommand(@NotNull GuardBackend backend) {
        this.backend = backend;
    }

    @Command(value = "areaban <details>", requiredSender = Player.class)
    @Permission(Permissions.AREABAN)
    @CommandDescription("Ban an ASN or a country for a while, or lift such a ban")
    public void areaban(CommandContext<Player> context, @Argument("details") @Greedy @NotNull String details) {
        handle(context.sender(), details);
    }

    /** Reads what the sender typed and makes the backend calls it asks for, in the sender's name. */
    void handle(@NotNull Player sender, @NotNull String details) {
        AreaBanArguments.Result result = AreaBanArguments.parse(details, sender::hasPermission);
        try {
            if (result instanceof AreaBanArguments.Ban ban) {
                AreaBanResponse created = this.backend.createAreaBan(sender.getUniqueId(), new AreaBanCreateRequest()
                        .kind(ban.kind().name()).value(ban.value()).reason(ban.reason()).durationSeconds(ban.durationSeconds()));
                sender.sendMessage(BlackholeTheme.prefixed(Component.text("Area ban " + created.getKind() + " " + created.getValue() + " is in force")));
            } else if (result instanceof AreaBanArguments.Lift lift) {
                lift(sender, lift);
            } else if (result instanceof AreaBanArguments.Invalid invalid) {
                sender.sendMessage(BlackholeTheme.prefixed(Component.text(invalid.message())));
            }
        } catch (ApiException e) {
            sender.sendMessage(GuardMessages.refusal(GuardRefusal.of(e)));
        }
    }

    private void lift(Player sender, AreaBanArguments.Lift lift) throws ApiException {
        AreaBanResponse active = this.backend.activeAreaBans().stream()
                .filter(ban -> ban.getKind().equals(lift.kind().name()) && ban.getValue().equals(lift.value()))
                .findFirst().orElse(null);
        if (active == null) {
            sender.sendMessage(BlackholeTheme.prefixed(Component.text("No active area ban for " + lift.kind().name() + " " + lift.value())));
            return;
        }
        this.backend.liftAreaBan(sender.getUniqueId(), active.getId(), lift.reason());
        sender.sendMessage(BlackholeTheme.prefixed(Component.text("Area ban " + lift.kind().name() + " " + lift.value() + " is lifted")));
    }
}
