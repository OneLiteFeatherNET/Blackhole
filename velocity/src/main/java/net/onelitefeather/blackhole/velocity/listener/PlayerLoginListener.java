package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.SessionInfoDTO;
import net.onelitefeather.blackhole.client.model.SessionOutcome;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.guard.GuardLoginDecision;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.evidence.EvidenceCounts;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.intel.SessionReporter;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Denies the login of players with an active punishment in a BAN section. The player's punishments
 * come from the backend profile, which the local mirror serves only while the event bus is connected; if
 * the backend does not answer, the login is allowed.
 *
 * @since 1.0.0
 * @version 1.1.0
 * @author theEvilReaper
 */
public final class PlayerLoginListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerLoginListener.class);
    private final PunishProfileApi punishProfileApi;
    private final PunishmentState state;
    private final PunishmentScreens screens;
    private final EvidenceCounts evidenceCounts;
    private final SessionReporter sessions;
    private final GuardLoginDecision guard;

    @Inject
    public PlayerLoginListener(
            @NotNull ApiClient apiClient, @NotNull PunishmentState state, @NotNull PunishmentScreens screens,
            @NotNull EvidenceCounts evidenceCounts, @NotNull SessionReporter sessions, @NotNull GuardLoginDecision guard
    ) {
        this.punishProfileApi = new PunishProfileApi(apiClient);
        this.state = state;
        this.screens = screens;
        this.evidenceCounts = evidenceCounts;
        this.sessions = sessions;
        this.guard = guard;
    }

    /**
     * Disconnects the player if they have an active punishment in a BAN section, and reports the login
     * with its outcome. The report never changes the outcome.
     *
     * @param event the event
     */
    @Subscribe
    public void onLogin(@NotNull LoginEvent event) {
        Player player = event.getPlayer();
        String uuidHash = UUIDConverter.convertToSHA(player.getUniqueId());

        Optional<PunishmentSyncMessage> ban;
        try {
            ban = this.state.onLogin(uuidHash);
        } catch (PunishmentState.Unavailable e) {
            LOGGER.error("Could not check the punishments of {}, allowing the login: {}", player.getUsername(), e.getMessage());
            this.sessions.reportStart(player, SessionOutcome.ALLOWED);
            return;
        }
        if (ban.isPresent()) {
            this.sessions.reportStart(player, SessionOutcome.DENIED);
            long evidence = this.evidenceCounts.of(ban.get().punishmentIdentifier()).orElse(0);
            event.setResult(ResultedEvent.ComponentResult.denied(
                    this.screens.screen(ban.get(), PunishmentScreens.localeOf(player), PunishmentScreens.Surface.DISCONNECT, false, evidence)));
            return;
        }
        GuardLoginDecision.Outcome access = this.guard.decide(uuidHash, player.getRemoteAddress().getAddress().getHostAddress(),
                player.hasPermission(Permissions.STAFF));
        if (access.refused()) {
            this.sessions.reportStart(player, SessionOutcome.DENIED);
            event.setResult(ResultedEvent.ComponentResult.denied(
                    this.screens.loginDenial(PunishmentScreens.localeOf(player), access.reason())));
            return;
        }
        this.sessions.reportStart(player, SessionOutcome.ALLOWED);
        recordSessionInfo(player, uuidHash);
    }

    private void recordSessionInfo(@NotNull Player player, @NotNull String uuidHash) {
        try {
            this.punishProfileApi.updateSessionInfo(uuidHash,
                    new SessionInfoDTO().owner(uuidHash).protocolVersion(player.getProtocolVersion().getProtocol()));
        } catch (ApiException e) {
            LOGGER.debug("Session info not recorded for {}: {}", player.getUsername(), e.getMessage());
        }
    }
}
