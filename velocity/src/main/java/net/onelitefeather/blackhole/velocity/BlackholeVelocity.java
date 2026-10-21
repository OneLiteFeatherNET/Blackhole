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
import net.onelitefeather.blackhole.velocity.command.PardonCommand;
import net.onelitefeather.blackhole.velocity.command.PunishCommand;
import net.onelitefeather.blackhole.velocity.command.PunishInfoCommand;
import net.onelitefeather.blackhole.velocity.command.EloCommand;
import net.onelitefeather.blackhole.velocity.command.TargetParser;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.listener.PlayerChatListener;
import net.onelitefeather.blackhole.velocity.listener.PlayerClientBrandListener;
import net.onelitefeather.blackhole.velocity.listener.PlayerDisconnectListener;
import net.onelitefeather.blackhole.velocity.listener.PlayerLoginListener;
import net.onelitefeather.blackhole.velocity.listener.ServerConnectListener;
import net.onelitefeather.blackhole.velocity.module.BlackholeClientModule;
import net.onelitefeather.blackhole.velocity.enforcement.EnforcementActions;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncHandler;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.punish.HttpBackendGateway;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
import net.onelitefeather.blackhole.velocity.section.BackendSectionSource;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.translation.GlobalTranslator;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.velocity.CloudInjectionModule;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
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
        try {
            this.client = BlackholeApiClients.create(this.config.getBaseUrl());
        } catch (IllegalArgumentException e) {
            this.logger.error("{} - the plugin cannot reach the backend until config.json is fixed.", e.getMessage());
            throw e;
        }
        this.logger.info("Initialized Blackhole client");

        Clock clock = Clock.systemUTC();
        this.redisSyncService = new RedisSyncService(this.config);
        SectionRegistry sections = new SectionRegistry(new BackendSectionSource(this.redisSyncService, this.client));
        PunishmentMirror mirror = new PunishmentMirror();
        BackendGateway backend = new HttpBackendGateway(this.client, this.redisSyncService);
        PunishmentState state = new PunishmentState(sections, mirror, this.redisSyncService, owner -> backend.profile(owner), clock);
        EnforcementActions actions = new EnforcementActions(this.server, sections, state, this.config, clock);
        PunishmentSyncHandler syncHandler = new PunishmentSyncHandler(mirror, actions);
        PunishService punishService = new PunishService(backend, sections);

        this.redisSyncService.onPunishmentSync(syncHandler::accept);
        // The reload calls the backend, so it must not run on the Redis callback thread.
        this.redisSyncService.onSectionChanged(changed -> CompletableFuture.runAsync(sections::reload));
        this.redisSyncService.connect();
        sections.reload();

        Injector childInjector = this.injector.createChildInjector(
                new BlackholeClientModule(this.client, this.config, this.redisSyncService, sections, mirror, state,
                        syncHandler, actions, backend, punishService, clock).asModule(),
                new CloudInjectionModule<>(
                        CommandSource.class,
                        ExecutionCoordinator.simpleCoordinator(),
                        SenderMapper.identity()
                ));
        final VelocityCommandManager<CommandSource> commandManager = childInjector.getInstance(
                Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {
                })
        );
        AnnotationParser<CommandSource> annotationParser = new AnnotationParser<>(commandManager, CommandSource.class);
        annotationParser.parse(childInjector.getInstance(TargetParser.class));
        annotationParser.parse(childInjector.getInstance(PunishCommand.class));
        annotationParser.parse(childInjector.getInstance(PunishInfoCommand.class));
        annotationParser.parse(childInjector.getInstance(PardonCommand.class));
        annotationParser.parse(childInjector.getInstance(EloCommand.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerLoginListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerChatListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerClientBrandListener.class));
        server.getEventManager().register(this, childInjector.getInstance(ServerConnectListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerDisconnectListener.class));
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
        GlobalTranslator.translator().addSource(store);
    }
}
