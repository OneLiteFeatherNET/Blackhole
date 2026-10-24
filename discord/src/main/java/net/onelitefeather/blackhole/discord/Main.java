package net.onelitefeather.blackhole.discord;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
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
import net.onelitefeather.blackhole.discord.metrics.Health;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.notify.EmbedRenderer;
import net.onelitefeather.blackhole.discord.notify.NotificationDispatcher;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler;
import net.onelitefeather.blackhole.discord.notify.RabbitConsumer;
import net.onelitefeather.blackhole.discord.sched.ExecutorScheduler;
import net.onelitefeather.blackhole.discord.sched.Scheduler;
import net.onelitefeather.blackhole.discord.sync.CaseEventHandler;
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
        CatchUp catchUp = new CatchUp(backend, discord, metrics);
        StaffAccessReconciler staff = new StaffAccessReconciler(backend, discord, registry, config.staffRoleId(), metrics);
        TicketLifecycle lifecycle = new TicketLifecycle(config.tickets(), config.guildId(), backend, discord, relay, catchUp, staff, registry, metrics, clock);
        ArchiveSweeper sweeper = new ArchiveSweeper(backend, lifecycle, config.tickets().deleteAfter(), metrics);
        PermissionSelfCheck selfCheck = new PermissionSelfCheck(discord, config.tickets(), config.staffRoleId(), health);
        SyncEngine engine = new SyncEngine(config.tickets(), backend, discord, lifecycle, relay, catchUp, staff, sweeper, selfCheck, registry,
                botScheduler, metrics, message -> { }, message -> { });
        AppealCommands appeals = new AppealCommands(backend, lifecycle);
        discord.appeals(appeals);
        discord.listener(engine);

        NotificationHandler handler = new NotificationHandler(backend, discord,
                new EmbedRenderer(config.panelUrl(), config.staffRoleId()), config.teamChannelId());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("rabbit-retry").daemon(true).factory());
        RabbitConsumer consumer = new RabbitConsumer(config.rabbit(), new NotificationDispatcher(handler), scheduler);
        CaseEventHandler caseEvents = new CaseEventHandler(engine);
        RabbitConsumer caseConsumer = new RabbitConsumer(config.rabbit(), "blackhole.discord.case", CaseEventHandler.ROUTING_KEYS, caseEvents::onMessage, scheduler);

        HealthServer healthServer = config.healthPort() > 0
                ? new HealthServer(config.healthPort(), () -> new HealthServer.Status(discord.connected(), health.problems()), metrics::render)
                : null;
        if (healthServer != null) {
            healthServer.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.close();
            caseConsumer.close();
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
        } catch (IllegalStateException e) {
            LOGGER.error("{}", e.getMessage());
            discord.close();
            System.exit(3);
        }
    }
}
