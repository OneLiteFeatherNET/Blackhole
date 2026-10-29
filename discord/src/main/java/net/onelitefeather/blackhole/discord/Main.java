package net.onelitefeather.blackhole.discord;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.discord.attachment.AttachmentFetcher;
import net.onelitefeather.blackhole.discord.audit.AuditEmbedRenderer;
import net.onelitefeather.blackhole.discord.audit.AuditWebhookPoster;
import net.onelitefeather.blackhole.discord.audit.JdkWebhookTransport;
import net.onelitefeather.blackhole.discord.audit.Sleeper;
import net.onelitefeather.blackhole.discord.audit.StaffActionHandler;
import net.onelitefeather.blackhole.discord.attachment.InboundFiles;
import net.onelitefeather.blackhole.discord.attachment.JdkAttachmentDownloader;
import net.onelitefeather.blackhole.discord.auth.ClientCredentialsTokenProvider;
import net.onelitefeather.blackhole.discord.auth.JdkTokenTransport;
import net.onelitefeather.blackhole.discord.auth.TokenSource;
import net.onelitefeather.blackhole.discord.backend.ApiClients;
import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.HttpBackendGateway;
import net.onelitefeather.blackhole.discord.command.AccountCommands;
import net.onelitefeather.blackhole.discord.command.AppealCommands;
import net.onelitefeather.blackhole.discord.config.BotConfig;
import net.onelitefeather.blackhole.discord.gateway.HealthServer;
import net.onelitefeather.blackhole.discord.gateway.JdaGateway;
import net.onelitefeather.blackhole.discord.leak.LeakScanner;
import net.onelitefeather.blackhole.discord.metrics.Health;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.notify.EmbedRenderer;
import net.onelitefeather.blackhole.discord.notify.GuardMessageRenderer;
import net.onelitefeather.blackhole.discord.notify.GuardNotificationHandler;
import net.onelitefeather.blackhole.discord.notify.NotificationDispatcher;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler;
import net.onelitefeather.blackhole.discord.notify.NatsConsumer;
import net.onelitefeather.blackhole.discord.sched.ExecutorScheduler;
import net.onelitefeather.blackhole.discord.sched.Scheduler;
import net.onelitefeather.blackhole.discord.command.StaffButtonHandler;
import net.onelitefeather.blackhole.discord.sync.CaseEventHandler;
import net.onelitefeather.blackhole.discord.sync.ControlMessageSync;
import net.onelitefeather.blackhole.discord.sync.CatchUp;
import net.onelitefeather.blackhole.discord.sync.OutboxRelay;
import net.onelitefeather.blackhole.discord.sync.SyncEngine;
import net.onelitefeather.blackhole.discord.ticket.ArchiveSweeper;
import net.onelitefeather.blackhole.discord.ticket.PermissionSelfCheck;
import net.onelitefeather.blackhole.discord.ticket.StaffAccessReconciler;
import net.onelitefeather.blackhole.discord.ticket.TicketLifecycle;
import net.onelitefeather.blackhole.discord.ticket.TicketRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Starts the Blackhole Discord bot: reads the environment, authenticates to the backend with the client
 * credentials grant (role {@code Blackhole.Discord}), connects to Discord and registers the slash
 * commands. The bot keeps no state of its own.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    /** The staff audit consumer, or null when no webhook is configured: no webhook, no consumer, no posts. */
    private static NatsConsumer auditConsumer(BotConfig config, Metrics metrics, ScheduledExecutorService scheduler) {
        if (config.audit().webhookUrl() == null) {
            LOGGER.info("The staff audit is off: {} is not set", BotConfig.ENV_AUDIT_WEBHOOK_URL);
            return null;
        }
        StaffActionHandler audit = new StaffActionHandler(
                new AuditWebhookPoster(new JdkWebhookTransport(config.audit().webhookUrl()), Sleeper.threads(), metrics),
                new AuditEmbedRenderer(config.panelUrl()), metrics);
        return new NatsConsumer(config.nats(), StaffActionHandler.DURABLE, StaffActionHandler.EVENT_TYPES, audit::onMessage,
                scheduler, StaffActionHandler.IN_FLIGHT, StaffActionHandler.ACK_WAIT, config.audit().maxDeliver(), audit::onExhausted);
    }

    public static void main(String[] args) throws Exception {
        BotConfig config;
        try {
            config = BotConfig.fromEnvironment(System::getenv);
        } catch (BotConfig.ConfigException e) {
            e.problems().forEach(problem -> LOGGER.error("Configuration: {}", problem));
            System.exit(2);
            return;
        }
        LOGGER.info("Starting the Blackhole Discord bot with {}", config);

        TokenSource tokens = new ClientCredentialsTokenProvider(config.auth(), new JdkTokenTransport(), Clock.systemUTC());
        ApiClient client = ApiClients.create(config.backendUrl(), tokens);
        BackendGateway backend = new HttpBackendGateway(client);
        JdaGateway discord = new JdaGateway(config, new AccountCommands(backend));

        // The ticket bot. One worker thread runs all of it, so the sync code never races with itself.
        Clock clock = Clock.systemUTC();
        Metrics metrics = new Metrics();
        Health health = new Health();
        TicketRegistry registry = new TicketRegistry();
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("bot-worker").daemon(true).factory());
        Scheduler botScheduler = new ExecutorScheduler(worker);
        OutboxRelay relay = new OutboxRelay(backend, discord, metrics);
        CatchUp catchUp = new CatchUp(backend, discord, metrics,
                new InboundFiles(backend, discord, new AttachmentFetcher(new JdkAttachmentDownloader()), metrics));
        StaffAccessReconciler staff = new StaffAccessReconciler(backend, discord, registry, config.staffRoleId(), metrics);
        TicketLifecycle lifecycle = new TicketLifecycle(config.tickets(), config.guildId(), backend, discord, relay, catchUp, staff, registry, metrics, clock);
        ArchiveSweeper sweeper = new ArchiveSweeper(backend, lifecycle, config.tickets().deleteAfter(), metrics);
        PermissionSelfCheck selfCheck = new PermissionSelfCheck(discord, config.tickets(), config.staffRoleId(), health);
        LeakScanner leakScanner = new LeakScanner(backend, discord, botScheduler, clock, metrics, config.leak(), config.tickets());
        SyncEngine engine = new SyncEngine(config.tickets(), backend, discord, lifecycle, relay, catchUp, staff, sweeper, selfCheck, registry,
                botScheduler, metrics, leakScanner::onMessage, leakScanner::onEdit);
        AppealCommands appeals = new AppealCommands(backend, lifecycle);
        discord.appeals(appeals);
        discord.listener(engine);

        NotificationHandler handler = new NotificationHandler(backend, discord,
                new EmbedRenderer(config.panelUrl(), config.staffRoleId()), config.teamChannelId());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("nats-retry").daemon(true).factory());
        GuardNotificationHandler guardAlerts = new GuardNotificationHandler(discord, new GuardMessageRenderer(config.panelUrl()), config.teamChannelId());
        NatsConsumer consumer = new NatsConsumer(config.nats(), new NotificationDispatcher(handler, guardAlerts), scheduler);
        CaseEventHandler caseEvents;
        if (config.staffActions()) {
            // The staff control message of each ticket channel, its buttons and the redraw on every case event (DISCORD_STAFF_ACTIONS).
            ControlMessageSync controls = new ControlMessageSync(config.panelUrl(), backend, discord, registry, metrics, clock);
            lifecycle.channelReady(controls::redraw);
            discord.staff(new StaffButtonHandler(backend, registry, lifecycle, controls));
            caseEvents = new CaseEventHandler(engine, controls::onCaseEvent);
        } else {
            caseEvents = new CaseEventHandler(engine);
        }
        NatsConsumer caseConsumer = new NatsConsumer(config.nats(), NatsConsumer.CASE_DURABLE, CaseEventHandler.ROUTING_KEYS, caseEvents::onMessage, scheduler);
        NatsConsumer auditConsumer = auditConsumer(config, metrics, scheduler);

        HealthServer healthServer = config.healthPort() > 0
                ? new HealthServer(config.healthPort(), () -> new HealthServer.Status(discord.connected(), health.problems()), metrics::render)
                : null;
        if (healthServer != null) {
            healthServer.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.close();
            caseConsumer.close();
            if (auditConsumer != null) {
                auditConsumer.close();
            }
            scheduler.shutdownNow();
            worker.shutdownNow();
            discord.close();
            if (healthServer != null) {
                healthServer.close();
            }
        }, "shutdown"));

        try {
            discord.start();
            engine.start();
            if (config.tickets().appealChannelId() != 0) {
                botScheduler.execute(() -> appeals.ensurePanel(discord, config.tickets().appealChannelId()));
            }
            // Events are only consumed once Discord is connected, so no notification is lost to a missing channel.
            consumer.start();
            caseConsumer.start();
            if (auditConsumer != null) {
                LOGGER.info("Posting staff actions to the audit webhook");
                auditConsumer.start();
            }
        } catch (IllegalStateException e) {
            LOGGER.error("{}", e.getMessage());
            discord.close();
            System.exit(3);
        }
    }
}
