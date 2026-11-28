package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.enforcement.EnforcementHealth;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceLoginGuardTest {

    private final EnforcementHealth health = new EnforcementHealth();

    private static Player player(boolean staff) {
        return (Player) Proxy.newProxyInstance(MaintenanceLoginGuardTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> UUID.nameUUIDFromBytes("guard-test".getBytes());
                    case "getUsername" -> "Steve";
                    case "hasPermission" -> staff && Permissions.STAFF.equals(args[0]);
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Steve";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aPlayerIsRefusedWithAMaintenanceMessageWhileEnforcementIsNotActive() {
        LoginEvent event = new LoginEvent(player(false));

        new MaintenanceLoginGuard(this.health, () -> true).onLogin(event);

        assertFalse(event.getResult().isAllowed(), "without enforcement a player could be banned and let in");
    }

    @Test
    void aStaffMemberMayStillLogInWhileEnforcementIsNotActive() {
        LoginEvent event = new LoginEvent(player(true));

        new MaintenanceLoginGuard(this.health, () -> true).onLogin(event);

        assertTrue(event.getResult().isAllowed(), "staff must be able to reach the proxy to repair it");
    }

    @Test
    void aPlayerMayLogInOnceEnforcementIsActive() {
        this.health.enforcing();
        LoginEvent event = new LoginEvent(player(false));

        new MaintenanceLoginGuard(this.health, () -> true).onLogin(event);

        assertTrue(event.getResult().isAllowed());
    }

    @Test
    void theGuardCanBeSwitchedOffByConfiguration() {
        LoginEvent event = new LoginEvent(player(false));

        new MaintenanceLoginGuard(this.health, () -> false).onLogin(event);

        assertTrue(event.getResult().isAllowed(), "an operator may prefer to let players in without enforcement");
    }
}
