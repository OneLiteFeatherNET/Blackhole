package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleCatalogueReporter;
import net.onelitefeather.blackhole.velocity.clientrule.CommandCatalogue;
import net.onelitefeather.blackhole.velocity.clientrule.CommandRuleEnforcer;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

/**
 * Feeds the command rules: {@code CommandExecuteEvent} (Velocity waits for it before handling or
 * forwarding the command, for proxy commands and for commands it forwards to a backend server) and
 * {@code PlayerAvailableCommandsEvent} (Velocity waits for it before it sends the command tree, which
 * includes the backend servers' commands). Only this class imports the events; the enforcer works on plain
 * values.
 */
public final class CommandRuleListener {

    private final CommandRuleEnforcer enforcer;
    private final CommandCatalogue commands;
    private final ClientRuleCatalogueReporter catalogue;

    public CommandRuleListener(@NotNull CommandRuleEnforcer enforcer, @NotNull CommandCatalogue commands, @NotNull ClientRuleCatalogueReporter catalogue) {
        this.enforcer = enforcer;
        this.commands = commands;
        this.catalogue = catalogue;
    }

    @Subscribe
    public void onCommand(@NotNull CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player player) {
            this.enforcer.onCommand(player, event);
        }
    }

    @Subscribe
    public void onAvailableCommands(@NotNull PlayerAvailableCommandsEvent event) {
        // Learn before hiding, so the editor also lists the commands a rule hides.
        if (this.commands.learn(event.getRootNode())) {
            this.catalogue.changed();
        }
        this.enforcer.hide(event.getPlayer(), event.getRootNode());
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        this.enforcer.onDisconnect(UUIDConverter.convertToSHA(event.getPlayer().getUniqueId()));
    }
}
