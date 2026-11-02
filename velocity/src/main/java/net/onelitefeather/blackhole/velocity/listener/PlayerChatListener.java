package net.onelitefeather.blackhole.velocity.listener;

import net.onelitefeather.blackhole.velocity.utils.LogIds;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloChatSignalRequest;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.enforcement.MuteChatDenial;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.evidence.EvidenceCounts;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/**
 * Denies the chat messages of muted players (an active punishment in a MUTE section) and forwards
 * the others to the backend for toxicity scoring. A muted player's message is never delivered: the
 * player is told about the mute, and when the message is signed and SignedVelocity is missing the
 * player is disconnected with the mute screen (see {@link MuteChatDenial}).
 */
public final class PlayerChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerChatListener.class);

    private final EloApi eloApi;
    private final PunishmentState state;
    private final EvidenceCounts evidenceCounts;
    private final MuteChatDenial denial;
    private final MuteNotices notices;

    @Inject
    public PlayerChatListener(
            @NotNull ApiClient blackholeClient, @NotNull PunishmentState state,
            @NotNull EvidenceCounts evidenceCounts, @NotNull MuteChatDenial denial, @NotNull MuteNotices notices
    ) {
        this(new EloApi(blackholeClient), state, evidenceCounts, denial, notices);
    }

    /** The ELO client is passed in so that tests can record the scoring calls. */
    public PlayerChatListener(
            @NotNull EloApi eloApi, @NotNull PunishmentState state,
            @NotNull EvidenceCounts evidenceCounts, @NotNull MuteChatDenial denial, @NotNull MuteNotices notices
    ) {
        this.eloApi = eloApi;
        this.state = state;
        this.evidenceCounts = evidenceCounts;
        this.denial = denial;
        this.notices = notices;
    }

    @Subscribe
    public void onChat(@NotNull PlayerChatEvent event) {
        Player player = event.getPlayer();
        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());

        Optional<PunishmentSyncMessage> mute;
        try {
            // Served from the local mirror once the player's state is known (zero network calls,
            // since this event fires on every message); unknown state is loaded once and cached.
            mute = this.state.activeMute(uuidHash);
        } catch (PunishmentState.Unavailable e) {
            LOGGER.error("Could not check the mute state of {}, holding the message back: {}", LogIds.of(uuidHash), e.getMessage());
            denyUnknownState(event, player, uuidHash);
            return;
        }
        if (mute.isPresent()) {
            long evidence = this.evidenceCounts.of(mute.get().punishmentIdentifier()).orElse(0);
            Locale locale = PunishmentScreens.localeOf(player);
            MuteChatDenial.Outcome outcome = this.denial.deny(event, player.getProtocolVersion());
            if (outcome == MuteChatDenial.Outcome.DISCONNECT) {
                // Named once per attempt, never with the message text: the proxy has no other way to stop it.
                LOGGER.info("Disconnected muted player {} for a signed chat message: SignedVelocity is not installed on this proxy",
                        LogIds.of(player.getUniqueId()));
                player.disconnect(this.notices.disconnectScreen(mute.get(), locale, evidence));
                return;
            }
            player.sendMessage(this.notices.notice(uuidHash, mute.get(), locale, evidence));
            return;
        }
        submitChatSignal(player, uuidHash, event.getMessage());
    }

    /**
     * The mute state is unknown and the backend did not answer: the message is held back with a neutral notice,
     * and the sender is never disconnected for it, since the player may not be muted at all. Signed chat without
     * SignedVelocity cannot be denied without Velocity disconnecting the sender, so it is delivered unchecked.
     */
    private void denyUnknownState(@NotNull PlayerChatEvent event, @NotNull Player player, @NotNull String uuidHash) {
        if (!this.denial.canDenyQuietly(player.getProtocolVersion())) {
            LOGGER.warn("Delivered chat of {} unchecked: signed chat cannot be held back without SignedVelocity", LogIds.of(uuidHash));
            return;
        }
        this.denial.deny(event, player.getProtocolVersion());
        player.sendMessage(this.notices.unavailable());
    }

    /**
     * Scores the message for the chat section's ELO. Best-effort - a failure here must never
     * affect chat delivery, which has already been decided by the mute check above.
     */
    private void submitChatSignal(@NotNull Player player, @NotNull String uuidHash, @NotNull String message) {
        try {
            this.eloApi.submitChatSignal(new EloChatSignalRequest().owner(uuidHash).message(message));
        } catch (ApiException e) {
            LOGGER.error("Failed to submit chat signal for ELO scoring for player {}: {}", LogIds.of(player.getUniqueId()), e.getMessage());
        }
    }
}
