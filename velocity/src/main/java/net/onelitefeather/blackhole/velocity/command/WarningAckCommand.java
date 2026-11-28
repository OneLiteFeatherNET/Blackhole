package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.enforcement.WarningHandler;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /blackhole warning ack <id>}: the player confirms a warning they received. Run by the
 * acknowledge line of the warning message. Open to every player; the owner check is in the handler.
 */
public final class WarningAckCommand {

    private final WarningHandler warnings;

    @Inject
    public WarningAckCommand(@NotNull WarningHandler warnings) {
        this.warnings = warnings;
    }

    @Command(value = "blackhole warning ack <id>", requiredSender = Player.class)
    @CommandDescription("Acknowledge a warning you received")
    public void acknowledge(CommandContext<Player> context, @Argument("id") @NotNull String id) {
        handle(context.sender(), id);
    }

    /** The behaviour of the command without the command framework, for tests. */
    void handle(@NotNull Player sender, @NotNull String id) {
        this.warnings.acknowledgeLater(sender, id);
    }
}
