package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.velocity.RecordingProxy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A player the client rule code can ask what it needs and nothing more; anything else fails the test
 * loudly. It records what is said to the player and whether they were disconnected. No proxy runs.
 */
public final class ClientPlayer {

    public final UUID uuid = UUID.randomUUID();
    public final String name;
    public final List<Component> messages = new ArrayList<>();
    public final List<Component> disconnects = new ArrayList<>();
    public final Set<String> permissions = new HashSet<>();
    public final int protocol;
    public String server;
    public boolean active = true;

    public ClientPlayer(String name, int protocol, String server) {
        this.name = name;
        this.protocol = protocol;
        this.server = server;
    }

    public Player player() {
        return (Player) Proxy.newProxyInstance(ClientPlayer.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> name;
                    case "getProtocolVersion" -> ProtocolVersion.getProtocolVersion(protocol);
                    case "isActive" -> active;
                    case "hasPermission" -> permissions.contains((String) args[0]);
                    case "getCurrentServer" -> Optional.ofNullable(server == null ? null : connection());
                    case "sendMessage" -> {
                        messages.add((Component) args[0]);
                        yield null;
                    }
                    case "disconnect" -> {
                        disconnects.add((Component) args[0]);
                        active = false;
                        yield null;
                    }
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ServerConnection connection() {
        return (ServerConnection) Proxy.newProxyInstance(ClientPlayer.class.getClassLoader(), new Class<?>[]{ServerConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> RecordingProxy.server(server).getServerInfo();
                    case "getServer" -> RecordingProxy.server(server);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
