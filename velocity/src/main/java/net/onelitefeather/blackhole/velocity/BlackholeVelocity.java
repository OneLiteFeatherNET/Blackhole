package net.onelitefeather.blackhole.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import net.onelitefeather.blackhole.velocity.module.BlackholeClientModule;
import net.onelitefeather.blackhole.web.BlackholeClient;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.velocity.CloudInjectionModule;
import org.incendo.cloud.velocity.VelocityCommandManager;

@Plugin(
        id = "blackhole-velocity",
        name = "Blackhole Velocity",
        version = "1.0.0"
)
public class BlackholeVelocity {

    private final Injector injector;

    @Inject
    public BlackholeVelocity(Injector injector) {
        this.injector = injector;
        String url = System.getProperty("blackhole.url", "empty");
        if (url.contains("empty")) {
            throw new IllegalStateException("Please set the blackhole.url system property.");
        }
        BlackholeClient client = BlackholeClient.newClient(url);
        this.injector.createChildInjector(new BlackholeClientModule(client));
    }

    @Subscribe
    public void onProxyInitialisation(ProxyInitializeEvent event) {
        final Injector childInjector = this.injector.createChildInjector(
                new CloudInjectionModule<>(
                        CommandSource.class,
                        ExecutionCoordinator.simpleCoordinator(),
                        SenderMapper.identity()
                )
        );

        final VelocityCommandManager<CommandSource> commandManager = childInjector.getInstance(
                Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {
                })
        );
    }
}
