package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import net.onelitefeather.blackhole.velocity.punish.StaffLinkService;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /blackhole link <code>} binds the executing player's account to the staff member who issued the
 * code in the admin UI; {@code /blackhole whoami} shows the current link. Players only: the player's UUID
 * is the proof the backend relies on.
 */
public final class LinkCommand {

    private final StaffLinkService service;

    @Inject
    public LinkCommand(@NotNull StaffLinkService service) {
        this.service = service;
    }

    @Command(value = "blackhole link <code>", requiredSender = Player.class)
    @Permission(Permissions.LINK)
    @CommandDescription("Link this Minecraft account to your staff account with a code from the admin panel")
    public void link(CommandContext<Player> context, @Argument("code") @NotNull String code) {
        Player player = context.sender();
        player.sendMessage(StaffMessages.linked(this.service.link(code, player.getUniqueId())));
    }

    @Command(value = "blackhole whoami", requiredSender = Player.class)
    @Permission(Permissions.LINK)
    @CommandDescription("Show which staff account this Minecraft account is linked to")
    public void whoami(CommandContext<Player> context) {
        Player player = context.sender();
        player.sendMessage(StaffMessages.whoami(this.service.whoami(player.getUniqueId())));
    }
}
