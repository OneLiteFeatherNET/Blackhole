package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.utils.UUIDConverter;
import net.onelitefeather.blackhole.request.profile.ProfileWebRequests;
import net.onelitefeather.blackhole.velocity.component.PunishProfileComponents;
import net.onelitefeather.blackhole.web.BlackholeClient;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public final class PunishInfoCommand {

    private final ProxyServer proxyServer;
    private final BlackholeClient blackholeClient;

    @Inject
    public PunishInfoCommand(@NotNull ProxyServer proxyServer, @NotNull BlackholeClient blackholeClient) {
        this.proxyServer = proxyServer;
        this.blackholeClient = blackholeClient;
    }

    @Command("blackhole <user> info")
    @Permission("blackhole.ban.info")
    @CommandDescription("Get information about a player's ban")
    public void banInfo(@NotNull CommandSource sender, @Argument("user") String user) {
        if (user.trim().isEmpty()) {
            sender.sendMessage(Component.text("Please provide a valid username."));
            return;
        }

        Optional<Player> fetchedTarget = this.proxyServer.getPlayer(user);

        if (fetchedTarget.isEmpty()) {
            sender.sendMessage(Component.text("The player is not online."));
            return;
        }

        Player player = fetchedTarget.get();
        ProfileWebRequests profileWebRequests = blackholeClient.profileRequests();

        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());
        Optional<PunishProfile> profileOptional = profileWebRequests.get(uuidHash);

        if (profileOptional.isEmpty()) {
            player.sendMessage(Component.text("No ban information found for player " + player.getUsername()));
            return;
        }

        PunishProfile profile = profileOptional.get();
        Component punishInfo = PunishProfileComponents.componentRepresentation(player.getUsername(), profile);

        player.sendMessage(punishInfo);
    }

    @Command("blackhole <user> history <sort>")
    @Permission("blackhole.ban.history")
    @CommandDescription("Get the ban history of a player")
    public void banHistory(@NotNull CommandSource source, @Argument("user") String user, @Argument("sort") String sort) {
        if (user.trim().isEmpty()) {
            source.sendMessage(Component.text("Please provide a valid username."));
            return;
        }

        Optional<Player> fetchedTarget = this.proxyServer.getPlayer(user);

        if (fetchedTarget.isEmpty()) {
            source.sendMessage(Component.text("The player is not online."));
            return;
        }

        Player player = fetchedTarget.get();

        ProfileWebRequests profileWebRequests = blackholeClient.profileRequests();

        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());
        Optional<PunishProfile> profileOptional = profileWebRequests.get(uuidHash);

        if (profileOptional.isEmpty()) {
            player.sendMessage(Component.text("No ban information found for player " + player.getUsername()));
            return;
        }

        PunishProfile profile = profileOptional.get();

        if (profile.history().isEmpty()) {
            player.sendMessage(Component.text("No ban history found for player " + player.getUsername()));
            return;
        }

        Component history = PunishProfileComponents.componentRepresentation(player.getUsername(), profile);
        player.sendMessage(history);
    }
}
