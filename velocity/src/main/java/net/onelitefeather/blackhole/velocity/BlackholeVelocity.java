package net.onelitefeather.blackhole.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.Configuration;
import net.onelitefeather.blackhole.client.model.PunishType;
import net.onelitefeather.blackhole.velocity.command.PunishCommand;
import net.onelitefeather.blackhole.velocity.command.PunishInfoCommand;
import net.onelitefeather.blackhole.velocity.command.PunishTypeScope;
import net.onelitefeather.blackhole.velocity.listener.PlayerLoginListener;
import net.onelitefeather.blackhole.velocity.module.BlackholeClientModule;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.velocity.CloudInjectionModule;
import org.incendo.cloud.velocity.VelocityCommandManager;

@Plugin(
        id = "blackhole-velocity",
        name = "Blackhole Velocity",
        version = "1.0.0",
        description = "A plugin to manage bans and mutes",
        authors = {"theEvilReaper", "TheMeinerLP"}
)
public class BlackholeVelocity {
    public static final CloudKey<PunishType> PUNISH_TYPE_KEY = CloudKey.of("punishType", PunishType.class);
    private final Injector injector;
    private final ProxyServer server;
    private ApiClient client;


    @Inject
    public BlackholeVelocity(Injector injector, ProxyServer server) {
        this.injector = injector;
        this.server = server;

    }

    @Subscribe
    public void onProxyInitialisation(ProxyInitializeEvent event) {
        this.client = Configuration.getDefaultApiClient();
        Injector childInjector = this.injector.createChildInjector(new BlackholeClientModule(this.client),  new CloudInjectionModule<>(
                CommandSource.class,
                ExecutionCoordinator.simpleCoordinator(),
                SenderMapper.identity()
        ));

        final VelocityCommandManager<CommandSource> commandManager = childInjector.getInstance(
                Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {
                })
        );
        AnnotationParser<CommandSource> annotationParser = new AnnotationParser<>(commandManager, CommandSource.class);
        annotationParser.registerBuilderModifier(
                PunishTypeScope.class,
                (typeScope, builder) -> builder.meta(PUNISH_TYPE_KEY, typeScope.value())
        );
        annotationParser.parse(childInjector.getInstance(PunishCommand.class));
        annotationParser.parse(childInjector.getInstance(PunishInfoCommand.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerLoginListener.class));
    }
}
