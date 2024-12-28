package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.configuration.PlayerFinishConfigurationEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.api.template.PunishTemplate;
import net.onelitefeather.blackhole.api.utils.UUIDConverter;
import net.onelitefeather.blackhole.web.BlackholeClient;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Listens for the PlayerFinishConfigurationEvent to disconnect players with active bans.
 * @since 1.0.0
 * @version 1.0.0
 * @author theEvilReaper
 */
public final class PlayerFinishConfigurationListener {

    private final BlackholeClient blackholeClient;

    @Inject
    public PlayerFinishConfigurationListener(@NotNull BlackholeClient blackholeClient) {
        this.blackholeClient = blackholeClient;
    }

    /**
     * Handles the logic to disconnect a player if they have an active ban
     * @param event the event
     */
    @Subscribe
    public void onFinishConfigurationEvent(@NotNull PlayerFinishConfigurationEvent event) {
        Player player = event.player();

        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());
        Optional<PunishProfile> profileOptional = this.blackholeClient.profileRequests().get(uuidHash);

        if (profileOptional.isEmpty()) return;

        PunishProfile punishProfile = profileOptional.get();

        if (punishProfile.activeBan().isEmpty()) return;

        PunishEntry activeBan = punishProfile.activeBan().get();
        PunishTemplate punishTemplate = activeBan.template();
        Component reason = Component.text(punishTemplate.reason());

        event.player().disconnect(reason);
    }
}
