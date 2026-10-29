package net.onelitefeather.blackhole.velocity.guard;

import com.velocitypowered.api.proxy.Player;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link Player} that only answers its identity and permission checks, which is all the guard asks.
 * Any other call fails the test loudly. No proxy needs to run.
 */
public final class StaffPlayers {

    private StaffPlayers() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** A player holding exactly the named permission nodes. */
    public static Player with(UUID uuid, String... granted) {
        Set<String> nodes = Set.of(granted);
        return (Player) Proxy.newProxyInstance(
                StaffPlayers.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hasPermission" -> nodes.contains((String) args[0]);
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "StaffPlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
