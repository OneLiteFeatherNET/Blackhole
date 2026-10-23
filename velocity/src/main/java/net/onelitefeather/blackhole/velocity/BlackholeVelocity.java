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
import net.onelitefeather.blackhole.velocity.command.CommandErrorHandlers;
import net.onelitefeather.blackhole.velocity.command.PardonCommand;
import net.onelitefeather.blackhole.velocity.command.PunishCommand;
import net.onelitefeather.blackhole.velocity.command.PunishInfoCommand;
import net.onelitefeather.blackhole.velocity.command.EloCommand;
import net.onelitefeather.blackhole.velocity.command.TargetParser;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.evidence.CachedEvidenceCounts;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidence;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
import net.onelitefeather.blackhole.velocity.evidence.EvidenceCounts;
import net.onelitefeather.blackhole.velocity.listener.ChatCaptureListener;
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
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
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
import org.incendo.cloud.brigadier.BrigadierSetting;
import org.incendo.cloud.velocity.CloudInjectionModule;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
        BackendGateway backend = new HttpBackendGateway(this.client, this.redisSyncService, clock);
        PunishmentState state = new PunishmentState(sections, mirror, this.redisSyncService, owner -> backend.profile(owner), clock);
        PunishmentScreens screens = new PunishmentScreens(clock, this.config, sections, GlobalTranslator.renderer());
        EnforcementActions actions = new EnforcementActions(this.server, sections, state, this.config, clock, screens);
        PunishmentSyncHandler syncHandler = new PunishmentSyncHandler(mirror, actions);
        ChatCaptureConfig chatCapture = this.config.getEvidence().getChatCapture();
        ChatBuffer chatBuffer = new ChatBuffer(clock, chatCapture);
        ChatEvidence chatEvidence = ChatEvidence.create(new ChatSnapshotBuilder(chatBuffer, chatCapture), backend, chatCapture);
        EvidenceCounts evidenceCounts = new CachedEvidenceCounts(backend, clock, chatCapture.getTimeout());
        PunishService punishService = new PunishService(backend, sections, chatEvidence);

        this.redisSyncService.onPunishmentSync(syncHandler::accept);
        // The reload calls the backend, so it must not run on the Redis callback thread.
        this.redisSyncService.onSectionChanged(changed -> CompletableFuture.runAsync(sections::reload));
        this.redisSyncService.connect();
        sections.reload();

        Injector childInjector = this.injector.createChildInjector(
                new BlackholeClientModule(this.client, this.config, this.redisSyncService, sections, mirror, state,
                        syncHandler, actions, backend, punishService, clock, screens, chatBuffer, evidenceCounts).asModule(),
                new CloudInjectionModule<>(
                        CommandSource.class,
                        ExecutionCoordinator.simpleCoordinator(),
                        SenderMapper.identity()
                ));
        final VelocityCommandManager<CommandSource> commandManager = childInjector.getInstance(
                Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {
                })
        );
        // Without this Brigadier rejects incomplete input ("elo") as an unknown command before Cloud can answer with the usage line.
        commandManager.brigadierManager().settings().set(BrigadierSetting.FORCE_EXECUTABLE, true);
        CommandErrorHandlers.register(commandManager);
        AnnotationParser<CommandSource> annotationParser = new AnnotationParser<>(commandManager, CommandSource.class);
        annotationParser.parse(childInjector.getInstance(TargetParser.class));
        annotationParser.parse(childInjector.getInstance(PunishCommand.class));
        annotationParser.parse(childInjector.getInstance(PunishInfoCommand.class));
        annotationParser.parse(childInjector.getInstance(PardonCommand.class));
        annotationParser.parse(childInjector.getInstance(EloCommand.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerLoginListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerChatListener.class));
        if (chatBuffer.enabled()) {
            this.logger.info("Chat capture is enabled: recent public chat is kept in memory for punishment evidence");
            server.getEventManager().register(this, childInjector.getInstance(ChatCaptureListener.class));
            // Lines are also dropped on insert; this frees the buffers of players who stopped chatting.
            server.getScheduler().buildTask(this, chatBuffer::evictExpired).repeat(Duration.ofMinutes(1)).schedule();
        }
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
        var store = MiniMessageTranslationStore.create(net.kyori.adventure.key.Key.key("blackhole", "translations"), BlackholeTheme.miniMessage());
        store.defaultLocale(Locale.ENGLISH);
        var classLoader = BlackholeVelocity.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, classLoader), false);
        store.registerAll(Locale.GERMAN, ResourceBundle.getBundle("blackhole", Locale.GERMAN, classLoader), false);
        GlobalTranslator.translator().addSource(store);
    }
}
