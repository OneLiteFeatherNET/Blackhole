package net.onelitefeather.blackhole.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A proxy with one player, recording what is done to the player. Everything the code under test does
 * not ask for fails the test loudly. No Velocity runs.
 */
public final class RecordingProxy {

    public final UUID uuid = UUID.randomUUID();
    public final List<Component> messages = new ArrayList<>();
    public final List<String> movedTo = new ArrayList<>();
    public final List<Component> disconnects = new ArrayList<>();

    private final String currentServer;
    private final Set<String> servers;

    /**
     * @param currentServer the server the player stands on, null for none
     * @param servers       the servers registered in the proxy
     */
    public RecordingProxy(String currentServer, String... servers) {
        this.currentServer = currentServer;
        this.servers = Set.of(servers);
    }

    public static RegisteredServer server(String name) {
        return (RegisteredServer) Proxy.newProxyInstance(RecordingProxy.class.getClassLoader(), new Class<?>[]{RegisteredServer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> new ServerInfo(name, InetSocketAddress.createUnresolved("localhost", 25565));
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    public Player player() {
        ServerConnection connection = currentServer == null ? null : (ServerConnection) Proxy.newProxyInstance(
                RecordingProxy.class.getClassLoader(), new Class<?>[]{ServerConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> server(currentServer).getServerInfo();
                    case "getServer" -> server(currentServer);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) Proxy.newProxyInstance(RecordingProxy.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> "Steve";
                    case "getEffectiveLocale" -> Locale.ENGLISH;
                    case "getCurrentServer" -> Optional.ofNullable(connection);
                    case "sendMessage" -> {
                        messages.add((Component) args[0]);
                        yield null;
                    }
                    case "disconnect" -> {
                        disconnects.add((Component) args[0]);
                        yield null;
                    }
                    case "createConnectionRequest" -> request(((RegisteredServer) args[0]).getServerInfo().getName());
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Steve";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    public ProxyServer proxy(Player player) {
        return (ProxyServer) Proxy.newProxyInstance(RecordingProxy.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllPlayers" -> List.of(player);
                    case "getServer" -> servers.contains((String) args[0]) ? Optional.of(server((String) args[0])) : Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ConnectionRequestBuilder request(String target) {
        return (ConnectionRequestBuilder) Proxy.newProxyInstance(RecordingProxy.class.getClassLoader(),
                new Class<?>[]{ConnectionRequestBuilder.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("connect")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    movedTo.add(target);
                    ConnectionRequestBuilder.Result result = (ConnectionRequestBuilder.Result) Proxy.newProxyInstance(
                            RecordingProxy.class.getClassLoader(), new Class<?>[]{ConnectionRequestBuilder.Result.class},
                            (p, m, a) -> m.getName().equals("isSuccessful") ? Boolean.TRUE : null);
                    return CompletableFuture.completedFuture(result);
                });
    }

    /** Screens in English, translated from the shipped bundle. */
    public static PunishmentScreens screens(SectionRegistry sections) {
        var store = MiniMessageTranslationStore.create(Key.key("blackhole", "test"), BlackholeTheme.miniMessage());
        store.defaultLocale(Locale.ENGLISH);
        ClassLoader loader = RecordingProxy.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, loader), false);
        return new PunishmentScreens(Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC), new BlackholeConfig(), sections,
                TranslatableComponentRenderer.usingTranslationSource(store));
    }
}
