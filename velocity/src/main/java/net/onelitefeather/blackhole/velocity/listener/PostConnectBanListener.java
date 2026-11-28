package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.enforcement.EnforcementActions;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the login window: a ban event that arrives between the login's backend read and the moment
 * the player is registered on the proxy finds no player to disconnect, and the login read predates
 * it. On the first connect after login the ban state is read again - the player is registered by then,
 * so any ban committed before this read is seen here, and any event after it reaches the connected
 * player through the mirror. Server switches do not read again.
 */
public final class PostConnectBanListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostConnectBanListener.class);

    private final PunishmentState state;
    private final EnforcementActions actions;

    @Inject
    public PostConnectBanListener(@NotNull PunishmentState state, @NotNull EnforcementActions actions) {
        this.state = state;
        this.actions = actions;
    }

    @Subscribe
    public void onServerPostConnect(@NotNull ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null) {
            return;
        }
        Player player = event.getPlayer();
        String owner = UUIDConverter.convertToSHA(player.getUniqueId());
        try {
            this.state.onLogin(owner).ifPresent(this.actions::enforce);
        } catch (PunishmentState.Unavailable e) {
            LOGGER.error("Could not re-check the bans of {} after login: {}", owner.substring(0, 8), e.getMessage());
        }
    }
}
