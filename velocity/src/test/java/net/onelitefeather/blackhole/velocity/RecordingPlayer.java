package net.onelitefeather.blackhole.velocity;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link Player} that records what it is sent. It knows its UUID and name and is on no server; any
 * other call fails the test loudly. No proxy needs to run.
 */
public final class RecordingPlayer {

    private final UUID uuid;
    private final String name;
    private final List<Component> messages = new ArrayList<>();

    public RecordingPlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public Player player() {
        return (Player) Proxy.newProxyInstance(
                RecordingPlayer.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> this.uuid;
                    case "getUsername" -> this.name;
                    case "getCurrentServer" -> Optional.empty();
                    case "sendMessage" -> {
                        if (args.length == 1 && args[0] instanceof Component component) {
                            this.messages.add(component);
                        }
                        yield null;
                    }
                    case "hashCode" -> this.uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "RecordingPlayer(" + this.name + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** @return every component sent to the player, oldest first */
    public List<Component> messages() {
        return List.copyOf(this.messages);
    }

    /** @return the plain text of every message sent to the player, oldest first */
    public List<String> texts() {
        return this.messages.stream().map(message -> PlainTextComponentSerializer.plainText().serialize(message)).toList();
    }
}
