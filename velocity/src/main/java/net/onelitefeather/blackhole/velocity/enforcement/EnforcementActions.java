package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Applies a newly active punishment to a player connected to this proxy: a BAN disconnects them, a
 * SERVER punishment moves them off the servers it covers. Runs for punishments applied from this
 * proxy's own commands and for sync messages from other proxies, so it must be safe to run twice.
 */
public final class EnforcementActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnforcementActions.class);

    private final ProxyServer proxy;
    private final SectionRegistry sections;
    private final PunishmentState state;
    private final BlackholeConfig config;
    private final Clock clock;
    private final PunishmentScreens screens;
    private final ServerResolver servers;

    public EnforcementActions(
            @NotNull ProxyServer proxy, @NotNull SectionRegistry sections, @NotNull PunishmentState state,
            @NotNull BlackholeConfig config, @NotNull Clock clock,
            @NotNull PunishmentScreens screens, @NotNull ServerResolver servers
    ) {
        this.proxy = proxy;
        this.sections = sections;
        this.state = state;
        this.config = config;
        this.clock = clock;
        this.screens = screens;
        this.servers = servers;
    }

    /**
     * The player with this owner hash, if connected to this proxy. The hash is one-way, so every
     * online player is hashed and compared.
     */
    public Optional<Player> findPlayer(@NotNull String owner) {
        for (Player player : this.proxy.getAllPlayers()) {
            if (UUIDConverter.convertToSHA(player.getUniqueId()).equals(owner)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    /**
     * Enforces an active punishment on its player if they are connected here. MUTE punishments need
     * no action: chat checks the mirror.
     */
    public void enforce(@NotNull PunishmentSyncMessage punishment) {
        if (!punishment.isActiveAt(this.clock.millis())) {
            return;
        }
        EnforcementKind kind = Enforcement.kindOf(punishment, this.sections).orElse(null);
        if (kind == null || kind == EnforcementKind.MUTE) {
            return;
        }
        findPlayer(punishment.owner()).ifPresent(player -> {
            if (kind == EnforcementKind.BAN) {
                player.disconnect(screen(player, punishment, PunishmentScreens.Surface.DISCONNECT, false));
            } else {
                moveOffCoveredServer(player, punishment);
            }
        });
    }

    private Component screen(Player player, PunishmentSyncMessage punishment, PunishmentScreens.Surface surface, boolean moved) {
        return this.screens.screen(punishment, PunishmentScreens.localeOf(player), surface, moved);
    }

    private void moveOffCoveredServer(Player player, PunishmentSyncMessage punishment) {
        String current = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
        if (current == null || !Enforcement.coversServer(punishment, this.sections, current, this.servers.facts(current), this.clock.millis())) {
            return;
        }
        String owner = punishment.owner();
        moveToFallback(player,
                moved -> screen(player, punishment, moved ? PunishmentScreens.Surface.CHAT : PunishmentScreens.Surface.DISCONNECT, moved),
                name -> this.state.activeServerBan(owner, name, this.servers.facts(name)).isPresent());
    }

    /**
     * Takes the player off the server they stand on: to the configured fallback server if it exists and
     * is usable, otherwise (or if the move fails) off the network. The one rule behind both the SERVER
     * punishments and the kicks of a server or section.
     *
     * @param screen   the text for the outcome; the argument is whether the player was moved (shown in
     *                 chat) or disconnected (shown as the disconnect screen)
     * @param unusable whether the named fallback server must not be used, e.g. the player is banned
     *                 from it or it is the server being kicked from
     * @return completes with true if the player was moved to the fallback, false if they were disconnected
     */
    public CompletableFuture<Boolean> moveToFallback(
            @NotNull Player player, @NotNull Function<Boolean, Component> screen, @NotNull Predicate<String> unusable
    ) {
        RegisteredServer fallback = this.proxy.getServer(this.config.getFallbackServer())
                .filter(server -> !unusable.test(server.getServerInfo().getName()))
                .orElse(null);
        if (fallback == null) {
            player.disconnect(screen.apply(false));
            return CompletableFuture.completedFuture(false);
        }
        player.sendMessage(screen.apply(true));
        return player.createConnectionRequest(fallback).connect().thenApply(result -> {
            if (result.isSuccessful()) {
                return true;
            }
            LOGGER.warn("Could not move {} to the fallback server, disconnecting instead", player.getUsername());
            player.disconnect(screen.apply(false));
            return false;
        });
    }
}
