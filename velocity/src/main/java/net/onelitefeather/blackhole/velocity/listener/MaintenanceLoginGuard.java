package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.enforcement.EnforcementHealth;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/**
 * Refuses non-staff logins while the proxy does not enforce punishments (its initialisation failed, or has not
 * completed). A ban that cannot be checked must not let a player in, so the login is refused with a maintenance
 * message. Staff may still log in to repair the proxy. Switched off by {@code startup.denyUnenforcedLogins}.
 */
public final class MaintenanceLoginGuard {

    static final Component MAINTENANCE = Component.text("Blackhole is starting up or under maintenance. Please try again in a few minutes.");

    private final EnforcementHealth health;
    private final BooleanSupplier enabled;

    /**
     * @param enabled whether the guard refuses logins at all; read at each login, so it may follow the configuration
     */
    public MaintenanceLoginGuard(@NotNull EnforcementHealth health, @NotNull BooleanSupplier enabled) {
        this.health = health;
        this.enabled = enabled;
    }

    @Subscribe
    public void onLogin(@NotNull LoginEvent event) {
        if (this.health.isEnforcing() || !this.enabled.getAsBoolean()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(Permissions.STAFF)) {
            return;
        }
        event.setResult(ResultedEvent.ComponentResult.denied(MAINTENANCE));
    }
}
