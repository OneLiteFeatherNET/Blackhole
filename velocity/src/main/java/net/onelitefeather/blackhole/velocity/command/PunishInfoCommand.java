package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.velocity.component.PunishProfileComponents;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public final class PunishInfoCommand {


    @Command("blackhole <user> info")
    @Permission("blackhole.user.info")
    @CommandDescription("Get information about a player's ban")
    public void userInfo(@NotNull CommandContext<Player> context, @Argument(value = "user", parserName = "profile") PunishProfile user) {
        Player targetPlayer = context.get(PunishCommand.TARGET_KEY);
        Component punishInfo = PunishProfileComponents.componentRepresentation(targetPlayer.getUsername(), user);

        context.sender().sendMessage(punishInfo);
    }

    @Command("blackhole <user> history <sort>")
    @Permission("blackhole.ban.history")
    @CommandDescription("Get the ban history of a player")
    public void banHistory(@NotNull CommandContext<Player> context, @Argument(value = "user", parserName = "profile") PunishProfile user, @Argument("sort") String sort) {
        Player targetPlayer = context.get(PunishCommand.TARGET_KEY);
        if (user.history().isEmpty()) {
            context.sender().sendMessage(Component.text("No ban history found for player " + targetPlayer.getUsername()));
            return;
        }

        Component history = PunishProfileComponents.componentRepresentation(targetPlayer.getUsername(), user);
        context.sender().sendMessage(history);
    }
}
