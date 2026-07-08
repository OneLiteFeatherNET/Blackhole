package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
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
    public void userInfo(@NotNull CommandContext<Player> context, @Argument(value = "user", parserName = "profile", suggestions = "players") PunishProfileDTO user) {
        PunishCommand.ResolvedTarget target = context.get(PunishCommand.TARGET_KEY);
        Component punishInfo = PunishProfileComponents.componentRepresentation(target.name(), user);

        context.sender().sendMessage(punishInfo);
    }

    @Command("blackhole <user> history <sort>")
    @Permission("blackhole.ban.history")
    @CommandDescription("Get the ban history of a player")
    public void banHistory(@NotNull CommandContext<Player> context, @Argument(value = "user", parserName = "profile", suggestions = "players") PunishProfileDTO user, @Argument("sort") String sort) {
        PunishCommand.ResolvedTarget target = context.get(PunishCommand.TARGET_KEY);

        Component history = PunishProfileComponents.historyRepresentation(target.name(), user);
        context.sender().sendMessage(history);
    }
}
