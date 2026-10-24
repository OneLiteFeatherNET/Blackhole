package net.onelitefeather.blackhole.discord.testing;

import net.onelitefeather.blackhole.discord.auth.MutableClock;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Opened;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.command.AppealCommands;
import net.onelitefeather.blackhole.discord.config.BotConfig.TicketSettings;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.IncomingMessage;
import net.onelitefeather.blackhole.discord.metrics.Health;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.config.BotConfig.LeakMode;
import net.onelitefeather.blackhole.discord.config.BotConfig.LeakSettings;
import net.onelitefeather.blackhole.discord.leak.LeakScanner;
import net.onelitefeather.blackhole.discord.sync.CaseEventHandler;
import net.onelitefeather.blackhole.discord.sync.CatchUp;
import net.onelitefeather.blackhole.discord.sync.OutboxRelay;
import net.onelitefeather.blackhole.discord.sync.SyncEngine;
import net.onelitefeather.blackhole.discord.ticket.ArchiveSweeper;
import net.onelitefeather.blackhole.discord.ticket.PermissionSelfCheck;
import net.onelitefeather.blackhole.discord.ticket.StaffAccessReconciler;
import net.onelitefeather.blackhole.discord.ticket.TicketLifecycle;
import net.onelitefeather.blackhole.discord.ticket.TicketRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The whole bot over fakes: a backend, a guild, a manual scheduler and a clock. A test builds one (or two, to simulate a
 * restart over the same backend and guild), acts on the fakes and calls {@link #tick()} to let the bot work.
 */
public final class Harness {

    public static final long GUILD = 1_000_000_000_000_001L;
    public static final long TICKET_CATEGORY = 11L;
    public static final long ARCHIVE_CATEGORY = 12L;
    public static final long APPEAL_CHANNEL = 13L;
    public static final long STAFF_ROLE = 77L;
    public static final long PLAYER = 123456789012345001L;
    public static final long STAFF_A = 123456789012346001L;
    public static final long STAFF_B = 123456789012346002L;

    public final MutableClock clock;
    public final ManualScheduler scheduler;
    public final FakeTicketBackend backend;
    public final FakeGuild guild;
    public final FakeDownloader downloader;
    public final Metrics metrics = new Metrics();
    public final Health health = new Health();
    public final TicketRegistry registry = new TicketRegistry();
    public final TicketSettings settings;
    public final TicketLifecycle lifecycle;
    public final SyncEngine engine;
    public final StaffAccessReconciler staff;
    public final PermissionSelfCheck selfCheck;
    public final ArchiveSweeper sweeper;
    public final AppealCommands appeals;
    public final CaseEventHandler caseEvents;
    public final LeakScanner leakScanner;
    public final List<IncomingMessage> outsideMessages = new ArrayList<>();
    public final List<IncomingMessage> outsideEdits = new ArrayList<>();

    /** A fresh world. */
    public Harness() {
        this(new LeakSettings(LeakMode.PUBLIC, java.util.Set.of()));
    }

    /** A fresh world with the given leak scan scope. */
    public Harness(LeakSettings leak) {
        this(new MutableClock(), null, null, Duration.ofDays(30), leak);
    }

    /** A fresh bot over an existing backend and guild, as after a restart. */
    public Harness(Harness previous) {
        this(previous.clock, previous.backend, previous.guild, Duration.ofDays(30));
    }

    public Harness(MutableClock clock, FakeTicketBackend existingBackend, FakeGuild existingGuild, Duration deleteAfter) {
        this(clock, existingBackend, existingGuild, deleteAfter, new LeakSettings(LeakMode.PUBLIC, java.util.Set.of()));
    }

    public Harness(MutableClock clock, FakeTicketBackend existingBackend, FakeGuild existingGuild, Duration deleteAfter, LeakSettings leak) {
        this.clock = clock;
        this.scheduler = new ManualScheduler(clock);
        this.backend = existingBackend == null ? new FakeTicketBackend() : existingBackend;
        this.guild = existingGuild == null ? new FakeGuild(clock, STAFF_ROLE) : existingGuild;
        this.settings = new TicketSettings(TICKET_CATEGORY, ARCHIVE_CATEGORY, APPEAL_CHANNEL, deleteAfter, Duration.ofSeconds(30), Duration.ofMinutes(5));
        OutboxRelay relay = new OutboxRelay(this.backend, this.guild, this.metrics);
        this.downloader = new FakeDownloader();
        CatchUp catchUp = new CatchUp(this.backend, this.guild, this.metrics,
                new net.onelitefeather.blackhole.discord.attachment.InboundFiles(this.backend, this.guild,
                        new net.onelitefeather.blackhole.discord.attachment.AttachmentFetcher(this.downloader), this.metrics));
        this.staff = new StaffAccessReconciler(this.backend, this.guild, this.registry, STAFF_ROLE, this.metrics);
        this.lifecycle = new TicketLifecycle(this.settings, GUILD, this.backend, this.guild, relay, catchUp, this.staff, this.registry, this.metrics, clock);
        this.sweeper = new ArchiveSweeper(this.backend, this.lifecycle, deleteAfter, this.metrics);
        this.selfCheck = new PermissionSelfCheck(this.guild, this.settings, STAFF_ROLE, this.health);
        this.leakScanner = new LeakScanner(this.backend, this.guild, this.scheduler, clock, this.metrics, leak, this.settings);
        Consumer<IncomingMessage> messages = message -> {
            this.outsideMessages.add(message);
            this.leakScanner.onMessage(message);
        };
        Consumer<IncomingMessage> edits = message -> {
            this.outsideEdits.add(message);
            this.leakScanner.onEdit(message);
        };
        this.engine = new SyncEngine(this.settings, this.backend, this.guild, this.lifecycle, relay, catchUp, this.staff, this.sweeper, this.selfCheck,
                this.registry, this.scheduler, this.metrics, messages, edits);
        this.appeals = new AppealCommands(this.backend, this.lifecycle);
        this.caseEvents = new CaseEventHandler(this.engine);
        this.guild.listener(this.engine);
        this.engine.start();
    }

    /** Lets the bot start up as it does when Discord reports ready, and run everything that is due. */
    public void ready() {
        this.engine.onReady();
        this.scheduler.runDue();
    }

    /** One sync interval passes: the poll and everything else that falls due runs. */
    public void tick() {
        this.scheduler.advance(Duration.ofSeconds(31));
    }

    public void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    /** Runs what is queued right now without moving time (a hint, a live event). */
    public void drain() {
        this.scheduler.runDue();
    }

    /** A ticket whose player is the standard test player, with the backend ready to open it. */
    public FakeTicketBackend.Rec newTicket(long no) {
        FakeTicketBackend.Rec rec = this.backend.add(no, Long.toString(PLAYER));
        return rec;
    }

    public void opens(FakeTicketBackend.Rec rec) {
        this.backend.openResult = new Result.Ok<>(new Opened(this.backend.view(rec.id), true, true));
    }

    /** A portal or panel message the bot must relay. */
    public OutboxItem portalMessage(FakeTicketBackend.Rec rec, long seq, AuthorKind author, String text) {
        OutboxItem item = new OutboxItem(seq, OutboxKind.ADD, java.util.UUID.randomUUID().toString(), author, text, null);
        this.backend.addOutbox(rec, item);
        return item;
    }

    public void staffRoleFor(long user) {
        this.guild.giveRole(user, STAFF_ROLE);
    }

    /** Runs the open flow for the standard player so the ticket has its channel. */
    public FakeTicketBackend.Rec openTicketWithChannel(long no) {
        FakeTicketBackend.Rec rec = newTicket(no);
        opens(rec);
        this.appeals.open(net.onelitefeather.blackhole.discord.command.Lang.EN, Long.toString(PLAYER), "Player", "K7M2-9QXA", "statement");
        return rec;
    }

    public long channelOf(FakeTicketBackend.Rec rec) {
        return Long.parseLong(rec.channelId);
    }
}
