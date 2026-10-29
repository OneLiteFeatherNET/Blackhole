package net.onelitefeather.blackhole.velocity.intel;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link Player} that only knows what a session report reads: its UUID, name, address, protocol and
 * the host typed. Any other call fails the test loudly. Each call of {@link #of} is a new connection.
 */
public final class SessionPlayer {

    public static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    public static final String ALICE_NAME = "Alice";

    private SessionPlayer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static Player alice() {
        return of(ALICE_ID, ALICE_NAME);
    }

    public static Player of(UUID uuid, String name) {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        return (Player) Proxy.newProxyInstance(
                SessionPlayer.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> name;
                    case "getRemoteAddress" -> address;
                    case "getProtocolVersion" -> ProtocolVersion.MINECRAFT_1_21;
                    case "hasPermission" -> false;
                    case "getVirtualHost" -> Optional.of(InetSocketAddress.createUnresolved("play.example.net", 25565));
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "SessionPlayer[" + name + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
