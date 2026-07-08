package net.onelitefeather.blackhole.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.Configuration;
import net.onelitefeather.blackhole.client.model.PunishType;
import net.onelitefeather.blackhole.velocity.command.PardonCommand;
import net.onelitefeather.blackhole.velocity.command.PunishCommand;
import net.onelitefeather.blackhole.velocity.command.PunishInfoCommand;
import net.onelitefeather.blackhole.velocity.command.PunishTypeScope;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.listener.PlayerChatListener;
import net.onelitefeather.blackhole.velocity.listener.PlayerClientBrandListener;
import net.onelitefeather.blackhole.velocity.listener.PlayerLoginListener;
import net.onelitefeather.blackhole.velocity.module.BlackholeClientModule;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.translation.GlobalTranslator;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.velocity.CloudInjectionModule;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;

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
    private final Path dataDirectory;
    private final Logger logger;
    private ApiClient client;
    private BlackholeConfig config;
    private RedisSyncService redisSyncService;


    @Inject
    public BlackholeVelocity(Injector injector, Logger logger, ProxyServer server, @DataDirectory Path dataDirectory) {
        this.injector = injector;
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialisation(ProxyInitializeEvent event) {
        registerTranslations();
        this.config = BlackholeConfig.load(dataDirectory.resolve("config.json"));
        this.logger.info("Loaded configuration with base URL: {}", config.getBaseUrl());
        this.client = Configuration.getDefaultApiClient();
        this.client.setBasePath(this.config.getBaseUrl());
        this.logger.info("Initialized Blackhole client");
        this.redisSyncService = new RedisSyncService(this.server, this.config, this.client);
        this.redisSyncService.connect();
        Injector childInjector = this.injector.createChildInjector(new BlackholeClientModule(this.client, this.config, this.redisSyncService),  new CloudInjectionModule<>(
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
        annotationParser.parse(childInjector.getInstance(PardonCommand.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerLoginListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerChatListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerClientBrandListener.class));
        server.getEventManager().register(this, this.redisSyncService);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.redisSyncService != null) {
            this.redisSyncService.shutdown();
        }
    }

    private void registerTranslations() {
        var store = MiniMessageTranslationStore.create(net.kyori.adventure.key.Key.key("blackhole", "translations"));
        store.defaultLocale(Locale.ENGLISH);
        var classLoader = BlackholeVelocity.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, classLoader), false);
        store.registerAll(Locale.GERMAN, ResourceBundle.getBundle("blackhole", Locale.GERMAN, classLoader), false);
        GlobalTranslator.get().addSource(store);
    }
}
