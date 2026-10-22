package net.onelitefeather.blackhole.velocity.evidence;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link Player} that only knows its UUID and the server it stands on, which is all the chat
 * listeners ask; any other call fails the test loudly. No proxy needs to run.
 */
public final class FakePlayer {

    private FakePlayer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** A player on the named backend server. */
    public static Player on(UUID uuid, String server) {
        ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
                FakePlayer.class.getClassLoader(), new Class<?>[]{ServerConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> new ServerInfo(server, InetSocketAddress.createUnresolved("localhost", 25565));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) Proxy.newProxyInstance(
                FakePlayer.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getCurrentServer" -> Optional.of(connection);
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakePlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A player who is on no backend server yet. */
    public static Player offServer(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                FakePlayer.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getCurrentServer" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
