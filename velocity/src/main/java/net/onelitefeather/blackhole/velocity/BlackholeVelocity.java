package net.onelitefeather.blackhole.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.velocity.auth.ClientCredentialsTokenProvider;
import net.onelitefeather.blackhole.velocity.auth.JdkTokenTransport;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.command.BackendAuthGuard;
import net.onelitefeather.blackhole.velocity.config.AuthConfig;
import net.onelitefeather.blackhole.velocity.command.CommandErrorHandlers;
import net.onelitefeather.blackhole.velocity.command.DiscordCommand;
import net.onelitefeather.blackhole.velocity.command.LinkCommand;
import net.onelitefeather.blackhole.velocity.command.PardonCommand;
import net.onelitefeather.blackhole.velocity.command.PunishCommand;
import net.onelitefeather.blackhole.velocity.command.PunishInfoCommand;
import net.onelitefeather.blackhole.velocity.command.EloCommand;
import net.onelitefeather.blackhole.velocity.command.ReportCommand;
import net.onelitefeather.blackhole.velocity.report.ReportEvidence;
import net.onelitefeather.blackhole.velocity.report.ReportFeedbackDelivery;
import net.onelitefeather.blackhole.velocity.listener.ReportFeedbackListener;
import net.onelitefeather.blackhole.velocity.report.ReportNotifier;
import net.onelitefeather.blackhole.velocity.report.ReportService;
import net.onelitefeather.blackhole.velocity.command.TargetParser;
import net.onelitefeather.blackhole.velocity.cloudnet.CatalogueReporter;
import net.onelitefeather.blackhole.velocity.cloudnet.CloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.HttpCatalogueSink;
import net.onelitefeather.blackhole.velocity.cloudnet.CloudNets;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.clientrule.BackendRuleSource;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleBackend;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleCatalogueReporter;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleCoverage;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleEnforcer;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleNotifier;
import net.onelitefeather.blackhole.velocity.clientrule.CommandCatalogue;
import net.onelitefeather.blackhole.velocity.clientrule.CommandDenial;
import net.onelitefeather.blackhole.velocity.clientrule.CommandRuleEnforcer;
import net.onelitefeather.blackhole.velocity.clientrule.HitReporter;
import net.onelitefeather.blackhole.velocity.clientrule.FingerprintSubmitter;
import net.onelitefeather.blackhole.velocity.clientrule.HttpClientRuleBackend;
import net.onelitefeather.blackhole.velocity.clientrule.RuleEngine;
import net.onelitefeather.blackhole.velocity.clientrule.RuleRegistry;
import net.onelitefeather.blackhole.velocity.clientrule.TraitCollector;
import net.onelitefeather.blackhole.velocity.clientrule.TraitTally;
import net.onelitefeather.blackhole.velocity.config.ClientRulesConfig;
import net.onelitefeather.blackhole.velocity.config.CloudNetConfig;
import net.onelitefeather.blackhole.velocity.listener.ClientRuleListener;
import net.onelitefeather.blackhole.velocity.listener.CommandRuleListener;
import net.onelitefeather.blackhole.velocity.enforcement.CachedBanIds;
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
import net.onelitefeather.blackhole.velocity.enforcement.MuteChatDenial;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNoticeCooldown;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncHandler;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.punish.HttpBackendGateway;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.redis.CachedTemplates;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
import net.onelitefeather.blackhole.velocity.section.BackendSectionSource;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.Locale;
import java.util.ResourceBundle;

@Plugin(
        id = "blackhole-velocity",
        name = "Blackhole Velocity",
        version = "1.0.0",
        description = "A plugin to manage bans and mutes",
        authors = {"theEvilReaper", "TheMeinerLP"},
        // Optional: loading after SignedVelocity lets muted signed chat be blocked instead of only announced.
        dependencies = @Dependency(id = MuteChatDenial.RELAY_PLUGIN_ID, optional = true)
)
public class BlackholeVelocity {
    /** After a full mute notice, further notices for the same mute are the one-line reminder for this long. */
    private static final Duration MUTE_NOTICE_COOLDOWN = Duration.ofSeconds(30);

    private final Injector injector;
    private final ProxyServer server;
    private final Path dataDirectory;
    private final Logger logger;
    private ApiClient client;
    private BlackholeConfig config;
    private RedisSyncService redisSyncService;
    private volatile boolean authReady;
    private CommandRuleListener commandRuleListener;

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
        Clock clock = Clock.systemUTC();
        TokenSource tokens = authenticate(clock);
        try {
            this.client = BlackholeApiClients.create(this.config.getBaseUrl(), tokens);
        } catch (IllegalArgumentException e) {
            this.logger.error("{} - the plugin cannot reach the backend until config.json is fixed.", e.getMessage());
            throw e;
        }
        this.logger.info("Initialized Blackhole client");

        this.redisSyncService = new RedisSyncService(this.config);
        SectionRegistry sections = new SectionRegistry(new BackendSectionSource(this.redisSyncService, this.client));
        PunishmentMirror mirror = new PunishmentMirror();
        BackendGateway backend = new HttpBackendGateway(this.client, new CachedTemplates(this.redisSyncService), clock);
        PunishmentState state = new PunishmentState(sections, mirror, this.redisSyncService, owner -> backend.profile(owner), clock);
        ChatCaptureConfig chatCapture = this.config.getEvidence().getChatCapture();
        PunishmentScreens screens = new PunishmentScreens(clock, this.config, sections, GlobalTranslator.renderer(),
                new CachedBanIds(backend, clock, chatCapture.getTimeout()));
        CloudNetConfig cloudNetConfig = this.config.getCloudnet().withEnvironment(System::getenv);
        CloudNetView cloudNet = CloudNets.detect(cloudNetConfig.getIntegration(), BlackholeVelocity.class.getClassLoader(), this.logger);
        ServerResolver servers = new ServerResolver(cloudNet, sections, this.logger::warn);
        EnforcementActions actions = new EnforcementActions(this.server, sections, state, this.config, clock, screens, servers);
        PunishmentSyncHandler syncHandler = new PunishmentSyncHandler(mirror, actions);
        ChatBuffer chatBuffer = new ChatBuffer(clock, chatCapture);
        ChatEvidence chatEvidence = ChatEvidence.create(new ChatSnapshotBuilder(chatBuffer, chatCapture), backend, chatCapture);
        EvidenceCounts evidenceCounts = new CachedEvidenceCounts(backend, clock, chatCapture.getTimeout());
        MuteChatDenial chatDenial = new MuteChatDenial(this.server.getPluginManager().isLoaded(MuteChatDenial.RELAY_PLUGIN_ID));
        if (!chatDenial.relayPresent()) {
            this.logger.warn("SignedVelocity is not installed: mutes cannot block signed chat and client rules cannot deny signed "
                    + "commands (Minecraft 1.19.1+). Muted players are told about their mute but their messages are still delivered; a command "
                    + "with signed arguments that a rule denies is still delivered and the hit is recorded as not enforced. "
                    + "Install SignedVelocity on the proxy and on every backend server to enforce mutes and command rules.");
        }
        PunishService punishService = new PunishService(backend, sections, chatEvidence);
        ReportService reportService = new ReportService(backend, ReportEvidence.create(new ChatSnapshotBuilder(chatBuffer, chatCapture), backend, chatCapture));

        // Registers its listener on the Redis service, so it must exist before connect().
        ReportNotifier reportNotifier = new ReportNotifier(this.server::getAllPlayers, this.redisSyncService, this.config::getPanelUrl);

        ReportFeedbackDelivery reportFeedback = new ReportFeedbackDelivery(backend, this.config::getZone, CompletableFuture::runAsync);
        this.redisSyncService.onReportFeedback(owner -> reportFeedback.poked(owner, this.server.getAllPlayers()));

        this.redisSyncService.onPunishmentSync(syncHandler::accept);
        // Registers its listener on the Redis service, so the notifier must exist before connect().
        ClientRulesConfig clientRulesConfig = this.config.getClientRules().withEnvironment(System::getenv);
        ClientRuleBackend ruleBackend = new HttpClientRuleBackend(this.client);
        RuleRegistry ruleRegistry = new RuleRegistry(new BackendRuleSource(this.redisSyncService, ruleBackend));
        ClientRuleNotifier ruleNotifier = new ClientRuleNotifier(this.server::getAllPlayers, this.redisSyncService);
        // The reload calls the backend, so it must not run on the Redis callback thread.
        this.redisSyncService.onClientRuleChanged(changed -> CompletableFuture.runAsync(ruleRegistry::reload));
        // The reload calls the backend, so it must not run on the Redis callback thread.
        this.redisSyncService.onSectionChanged(changed -> CompletableFuture.runAsync(sections::reload));
        this.redisSyncService.connect();
        sections.reload();
        long cloudNetSections = sections.all().stream().filter(section -> section.targetKind() != ServerTargetKind.PATTERN).count();
        if (!cloudNet.available() && cloudNetSections > 0) {
            this.logger.warn("{} CloudNet section(s) exist but CloudNet is not available: they cover no server and are not enforced", cloudNetSections);
        }

        startCatalogueReporter(cloudNet, cloudNetConfig);
        TraitCollector traitCollector = new TraitCollector();
        ClientRuleListener clientRuleListener = null;
        if (clientRulesConfig.isEnabled()) {
            ruleRegistry.reload();
            clientRuleListener = createClientRules(clientRulesConfig, ruleBackend, ruleRegistry, ruleNotifier, traitCollector,
                    new ClientRuleCoverage(sections, servers), cloudNet, cloudNetConfig, clock);
            this.logger.info("Client rules are enabled: {} rule(s) loaded", ruleRegistry.rules().size());
        } else {
            this.logger.info("Client rules are switched off on this proxy");
        }

        Injector childInjector = this.injector.createChildInjector(
                new BlackholeClientModule(this.client, this.config, this.redisSyncService, sections, mirror, state,
                        syncHandler, actions, backend, punishService, clock, screens, chatBuffer, evidenceCounts, chatDenial,
                        new MuteNotices(screens, new MuteNoticeCooldown(clock, MUTE_NOTICE_COOLDOWN)), servers, reportService, reportNotifier, reportFeedback).asModule(),
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
        BackendAuthGuard.register(commandManager, () -> this.authReady);
        AnnotationParser<CommandSource> annotationParser = new AnnotationParser<>(commandManager, CommandSource.class);
        annotationParser.parse(childInjector.getInstance(TargetParser.class));
        annotationParser.parse(childInjector.getInstance(PunishCommand.class));
        annotationParser.parse(childInjector.getInstance(PunishInfoCommand.class));
        annotationParser.parse(childInjector.getInstance(PardonCommand.class));
        annotationParser.parse(childInjector.getInstance(EloCommand.class));
        annotationParser.parse(childInjector.getInstance(LinkCommand.class));
        annotationParser.parse(childInjector.getInstance(ReportCommand.class));
        annotationParser.parse(childInjector.getInstance(DiscordCommand.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerLoginListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerChatListener.class));
        server.getEventManager().register(this, childInjector.getInstance(ReportFeedbackListener.class));
        if (chatBuffer.enabled()) {
            this.logger.info("Chat capture is enabled: recent public chat is kept in memory for punishment evidence");
            server.getEventManager().register(this, childInjector.getInstance(ChatCaptureListener.class));
            // Lines are also dropped on insert; this frees the buffers of players who stopped chatting.
            server.getScheduler().buildTask(this, chatBuffer::evictExpired).repeat(Duration.ofMinutes(1)).schedule();
        }
        server.getEventManager().register(this, childInjector.getInstance(PlayerClientBrandListener.class));
        server.getEventManager().register(this, childInjector.getInstance(ServerConnectListener.class));
        server.getEventManager().register(this, childInjector.getInstance(PlayerDisconnectListener.class));
        if (clientRuleListener != null) {
            server.getEventManager().register(this, clientRuleListener);
            server.getEventManager().register(this, this.commandRuleListener);
        }
    }

    /** Builds the client rule parts and starts the catalogue report; the returned listener is registered by the caller. */
    private ClientRuleListener createClientRules(ClientRulesConfig rulesConfig, ClientRuleBackend ruleBackend, RuleRegistry ruleRegistry,
                                                 ClientRuleNotifier notifier, TraitCollector traitCollector, ClientRuleCoverage coverage,
                                                 CloudNetView cloudNet, CloudNetConfig cloudNetConfig, Clock clock) {
        CatalogueReporter.Timer timer = timer();
        Executor async = CompletableFuture::runAsync;
        HitReporter hitReporter = new HitReporter(ruleBackend, notifier, async);
        RuleEngine ruleEngine = new RuleEngine(coverage);
        ClientRuleEnforcer enforcer = new ClientRuleEnforcer(traitCollector, ruleRegistry, ruleEngine, hitReporter);
        TraitTally tally = new TraitTally(clock, Duration.ofHours(24));
        FingerprintSubmitter fingerprints = new FingerprintSubmitter(traitCollector, ruleBackend, timer, clock, rulesConfig.getSettle(), async);
        String proxyId = cloudNetConfig.getProxyId().or(cloudNet::ownName).orElse("velocity").replaceAll("[^A-Za-z0-9_.-]", "-");
        CommandCatalogue commands = new CommandCatalogue();
        ClientRuleCatalogueReporter catalogue = new ClientRuleCatalogueReporter(tally, commands::words, ruleBackend, timer,
                proxyId.length() > 64 ? proxyId.substring(0, 64) : proxyId, rulesConfig.getCatalogue().getMinPlayers(),
                rulesConfig.getSettle(), rulesConfig.getCatalogue().getHeartbeat());
        catalogue.start();
        MuteChatDenial signed = new MuteChatDenial(this.server.getPluginManager().isLoaded(MuteChatDenial.RELAY_PLUGIN_ID));
        CommandRuleEnforcer commandRules = new CommandRuleEnforcer(ruleRegistry, ruleEngine, hitReporter, new CommandDenial(signed.relayPresent()), clock);
        this.commandRuleListener = new CommandRuleListener(commandRules, commands, catalogue);
        return new ClientRuleListener(traitCollector, enforcer, tally, fingerprints, catalogue);
    }

    /** Velocity's scheduler as the timer of the periodic reporters. */
    private CatalogueReporter.Timer timer() {
        return new CatalogueReporter.Timer() {
            @Override
            public void after(Duration delay, Runnable task) {
                server.getScheduler().buildTask(BlackholeVelocity.this, task).delay(delay).schedule();
            }

            @Override
            public void every(Duration period, Runnable task) {
                server.getScheduler().buildTask(BlackholeVelocity.this, task).delay(period).repeat(period).schedule();
            }
        };
    }

    /** Reports CloudNet's tasks, groups and services to the backend for the section editor; skipped without CloudNet. */
    private void startCatalogueReporter(CloudNetView cloudNet, CloudNetConfig cloudNetConfig) {
        if (!cloudNet.available()) {
            return;
        }
        String proxyId = cloudNetConfig.getProxyId().or(cloudNet::ownName).orElse(null);
        if (proxyId == null) {
            this.logger.warn("The CloudNet catalogue is not reported: the proxy has no CloudNet service name, set cloudnet.proxyId");
            return;
        }
        CatalogueReporter.Timer timer = new CatalogueReporter.Timer() {
            @Override
            public void after(Duration delay, Runnable task) {
                server.getScheduler().buildTask(BlackholeVelocity.this, task).delay(delay).schedule();
            }

            @Override
            public void every(Duration period, Runnable task) {
                server.getScheduler().buildTask(BlackholeVelocity.this, task).delay(period).repeat(period).schedule();
            }
        };
        new CatalogueReporter(cloudNet, new HttpCatalogueSink(this.client), timer, this.logger, proxyId,
                cloudNetConfig.getReportDebounce(), cloudNetConfig.getHeartbeat()).start();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.redisSyncService != null) {
            this.redisSyncService.shutdown();
        }
    }

    /**
     * Builds the source of the backend tokens from the file and the environment. Without usable
     * credentials it logs an error and returns a source that has no token: staff commands then answer
     * with a configuration message, while the Redis-based enforcement keeps running.
     */
    private TokenSource authenticate(Clock clock) {
        AuthConfig auth = this.config.getAuth().withEnvironment(System::getenv);
        try {
            if (!auth.isComplete()) {
                this.logger.error("The backend credentials are incomplete, missing: {}. Backend calls and staff commands "
                        + "are disabled until they are set in config.json or the environment; Redis enforcement continues.",
                        auth.missing());
                return TokenSource.unavailable("The backend credentials are incomplete, missing: " + auth.missing());
            }
            TokenSource tokens = new ClientCredentialsTokenProvider(auth, new JdkTokenTransport(), clock);
            this.authReady = true;
            this.logger.info("Authenticating to the backend as client {} at {}", auth.getClientId(), auth.tokenUri());
            return tokens;
        } catch (IllegalArgumentException e) {
            this.logger.error("{} - backend calls and staff commands are disabled until config.json is fixed.", e.getMessage());
            return TokenSource.unavailable(e.getMessage());
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
