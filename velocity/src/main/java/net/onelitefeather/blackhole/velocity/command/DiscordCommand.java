package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import net.onelitefeather.blackhole.velocity.punish.DiscordLinkService;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /blackhole discord} shows the executing player a one-time code to link their Discord account
 * with {@code /link <code>} in Discord. Open to every player: it only proves the player's own account.
 */
public final class DiscordCommand {

    private final DiscordLinkService service;

    @Inject
    public DiscordCommand(@NotNull DiscordLinkService service) {
        this.service = service;
    }

    @Command(value = "blackhole discord", requiredSender = Player.class)
    @CommandDescription("Get a code to link your Discord account")
    public void discord(CommandContext<Player> context) {
        Player player = context.sender();
        player.sendMessage(StaffMessages.discordCode(this.service.issueCode(player.getUniqueId())));
    }
}
