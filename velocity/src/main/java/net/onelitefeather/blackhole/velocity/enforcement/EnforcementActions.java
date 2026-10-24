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
        RegisteredServer fallback = this.proxy.getServer(this.config.getFallbackServer())
                .filter(server -> {
                    String name = server.getServerInfo().getName();
                    return this.state.activeServerBan(owner, name, this.servers.facts(name)).isEmpty();
                })
                .orElse(null);
        if (fallback == null) {
            player.disconnect(screen(player, punishment, PunishmentScreens.Surface.DISCONNECT, false));
            return;
        }
        player.sendMessage(screen(player, punishment, PunishmentScreens.Surface.CHAT, true));
        player.createConnectionRequest(fallback).connect().thenAccept(result -> {
            if (!result.isSuccessful()) {
                LOGGER.warn("Could not move {} to the fallback server, disconnecting instead", player.getUsername());
                player.disconnect(screen(player, punishment, PunishmentScreens.Surface.DISCONNECT, false));
            }
        });
    }
}
