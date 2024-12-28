package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.utils.UUIDConverter;
import net.onelitefeather.blackhole.web.BlackholeClient;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public final class PlayerChatListener {

    private final BlackholeClient blackholeClient;

    @Inject
    public PlayerChatListener(@NotNull BlackholeClient blackholeClient) {
        this.blackholeClient = blackholeClient;
    }

    @Subscribe
    public void onChat(@NotNull PlayerChatEvent event) {
        Player player = event.getPlayer();

        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());
        Optional<PunishProfile> profileOptional = this.blackholeClient.profileRequests().get(uuidHash);

        if (profileOptional.isEmpty()) return;

        PunishProfile punishProfile = profileOptional.get();

        if (punishProfile.activeBan().isEmpty()) return;

       // event.setResult(PlayerChatEvent.ChatResult.denied());
    }
}
