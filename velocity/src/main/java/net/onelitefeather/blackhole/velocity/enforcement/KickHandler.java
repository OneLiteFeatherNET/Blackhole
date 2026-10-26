package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.section.ServerTargets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Acts on the backend's kick events and reports what this proxy did. A kick takes a player off the
 * network (disconnect with the kick screen) or off a server or section (moved to the fallback server by
 * the same rule as a SERVER punishment, {@link EnforcementActions#moveToFallback}). A kick is a moment:
 * nothing is stored here and the punishment cache is never touched.
 *
 * <p>The reason is read with {@code GET /kick/{id}} (the event never carries it); if that fails the
 * player is kicked with the default text all the same. The result goes back over HTTP with the proxy's
 * machine token, never over the bus, which the proxy only listens to. A failed acknowledgement is logged
 * and changes nothing for the player.</p>
 *
 * <p>Methods make backend calls, so they must not run on the NATS thread.</p>
 */
public final class KickHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(KickHandler.class);

    private final EnforcementActions actions;
    private final SectionRegistry sections;
    private final ServerResolver servers;
    private final PunishmentScreens screens;
    private final BackendGateway backend;
    private final String proxyId;

    /**
     * @param proxyId the configured name of this proxy, as the backend stores it per acknowledgement
     */
    public KickHandler(
            @NotNull EnforcementActions actions, @NotNull SectionRegistry sections, @NotNull ServerResolver servers,
            @NotNull PunishmentScreens screens, @NotNull BackendGateway backend, @NotNull String proxyId
    ) {
        this.actions = actions;
        this.sections = sections;
        this.servers = servers;
        this.screens = screens;
        this.backend = backend;
        this.proxyId = proxyId;
    }

    /** Handles the kick of one player. */
    public void kick(@NotNull KickMessage message) {
        try {
            Optional<Player> found = this.actions.findPlayer(message.owner());
            if (found.isEmpty()) {
                acknowledge(message.kickId(), false, false);
                return;
            }
            Player player = found.get();
            String scope = message.scope();
            if (!isKnownScope(scope)) {
                LOGGER.warn("Ignoring a kick with the unknown scope {}", scope);
                return;
            }
            if (scope.equals("NETWORK")) {
                String reason = reasonOf(message.kickId());
                player.disconnect(this.screens.kick(PunishmentScreens.localeOf(player), PunishmentScreens.Surface.KICK, reason, false));
                acknowledge(message.kickId(), true, true);
                return;
            }
            String current = currentServer(player);
            if (current == null || !covers(scope, message.target(), current)) {
                acknowledge(message.kickId(), true, false);
                return;
            }
            String reason = reasonOf(message.kickId());
            moveOff(player, scope, message.target(), current, reason).whenComplete((removed, failure) ->
                    acknowledge(message.kickId(), true, failure == null));
        } catch (RuntimeException e) {
            LOGGER.error("Could not handle the kick {}: {}", message.kickId(), e.getMessage());
        }
    }

    /** Handles the kick of everybody on a server or section of this proxy; one answer per proxy. */
    public void mass(@NotNull MassKickMessage message) {
        try {
            String scope = message.scope();
            if (!isKnownScope(scope) || scope.equals("NETWORK")) {
                LOGGER.warn("Ignoring a mass kick with the unusable scope {}", scope);
                return;
            }
            List<Player> matching = new ArrayList<>();
            for (Player player : this.actions.onlinePlayers()) {
                String current = currentServer(player);
                if (current != null && covers(scope, message.target(), current)) {
                    matching.add(player);
                }
            }
            if (matching.isEmpty()) {
                acknowledge(message.actionId(), false, false);
                return;
            }
            String reason = reasonOf(message.actionId());
            List<CompletableFuture<Boolean>> removals = new ArrayList<>();
            for (Player player : matching) {
                removals.add(moveOff(player, scope, message.target(), currentServer(player), reason)
                        .handle((removed, failure) -> failure == null));
            }
            CompletableFuture.allOf(removals.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) ->
                    acknowledge(message.actionId(), true, removals.stream().anyMatch(removal -> removal.join())));
        } catch (RuntimeException e) {
            LOGGER.error("Could not handle the mass kick {}: {}", message.actionId(), e.getMessage());
        }
    }

    /**
     * Takes the player off the server they are on: to the fallback, which must not be the server being
     * kicked from nor covered by the target, otherwise off the network. Completes when it is done; the
     * player is gone from the target either way.
     */
    private CompletableFuture<Boolean> moveOff(Player player, String scope, @Nullable String target, String current, @Nullable String reason) {
        Component moved = this.screens.kick(PunishmentScreens.localeOf(player), PunishmentScreens.Surface.CHAT, reason, true);
        Component disconnected = this.screens.kick(PunishmentScreens.localeOf(player), PunishmentScreens.Surface.KICK, reason, false);
        return this.actions.moveToFallback(player, wasMoved -> wasMoved ? moved : disconnected,
                name -> name.equalsIgnoreCase(current) || covers(scope, target, name));
    }

    private boolean covers(String scope, @Nullable String target, String serverName) {
        if (target == null) {
            return false;
        }
        return switch (scope) {
            case "SERVER" -> target.equalsIgnoreCase(serverName);
            case "SECTION" -> this.sections.find(target)
                    .map(section -> ServerTargets.covers(section, serverName, this.servers.facts(serverName)))
                    .orElse(false);
            default -> false;
        };
    }

    private static boolean isKnownScope(String scope) {
        return scope.equals("NETWORK") || scope.equals("SERVER") || scope.equals("SECTION");
    }

    private static @Nullable String currentServer(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
    }

    /** The reason of the kick, null if the backend cannot be read: the screen then shows the default text. */
    private @Nullable String reasonOf(String id) {
        try {
            KickResponse kick = this.backend.kickStatus(id);
            return kick == null ? null : kick.getReason();
        } catch (ApiException | RuntimeException e) {
            LOGGER.warn("Could not read the reason of the kick {}, using the default text: {}", id, e.getMessage());
            return null;
        }
    }

    private void acknowledge(String id, boolean found, boolean moved) {
        try {
            this.backend.acknowledgeKick(id, this.proxyId, found, moved);
        } catch (ApiException | RuntimeException e) {
            LOGGER.warn("Could not report the kick {} to the backend: {}", id, e.getMessage());
        }
    }
}
