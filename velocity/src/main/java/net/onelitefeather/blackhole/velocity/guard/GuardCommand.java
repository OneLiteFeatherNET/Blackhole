package net.onelitefeather.blackhole.velocity.guard;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.GuardStateResponse;
import net.onelitefeather.blackhole.velocity.component.GuardMessages;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /guard} shows the join guard level, {@code /guard off|strict|lockdown} sets the manual level. The
 * argument reading is in {@link GuardArguments}; this class only makes the one backend call.
 */
public final class GuardCommand {

    private final GuardBackend backend;

    @Inject
    public GuardCommand(@NotNull GuardBackend backend) {
        this.backend = backend;
    }

    @Command(value = "guard", requiredSender = Player.class)
    @Permission(Permissions.GUARD)
    @CommandDescription("Show the join guard level")
    public void status(CommandContext<Player> context) {
        handle(context.sender(), "");
    }

    @Command(value = "guard <level>", requiredSender = Player.class)
    @Permission(Permissions.GUARD)
    @CommandDescription("Set the manual join guard level: off, strict or lockdown")
    public void set(CommandContext<Player> context, @Argument("level") @NotNull String level) {
        handle(context.sender(), level);
    }

    /** Reads what the sender typed and makes the one backend call it asks for, in the sender's name. */
    void handle(@NotNull Player sender, @NotNull String input) {
        send(sender, GuardArguments.parse(input));
    }

    private void send(Player sender, GuardArguments.Result result) {
        try {
            if (result instanceof GuardArguments.Status) {
                sender.sendMessage(describe(this.backend.state()));
            } else if (result instanceof GuardArguments.SetLevel set) {
                sender.sendMessage(describe(this.backend.setLevel(sender.getUniqueId(), set.level().name())));
            } else if (result instanceof GuardArguments.Invalid invalid) {
                sender.sendMessage(BlackholeTheme.prefixed(Component.text(invalid.message())));
            }
        } catch (ApiException e) {
            sender.sendMessage(GuardMessages.refusal(GuardRefusal.of(e)));
        }
    }

    private static Component describe(GuardStateResponse state) {
        return BlackholeTheme.prefixed(Component.text("Join guard: " + state.getEffectiveLevel() + " (manual " + state.getManualLevel()
                + ", unattended " + state.getAutomaticLevel() + ")"));
    }
}
