package net.onelitefeather.blackhole.backend.casetimeline;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseConversationService;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Ban ID is a credential: the conversation, leak, reissue and export flows leave it out of logs, events, the timeline and the export. */
class CaseConversationNoLeakTest {

    private static final String ID = "CCCCCCCCCCCCCCCCCCCCCC";
    private static final Actor ADMIN = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false, true);

    private final TestClock clock = new TestClock();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore store = new FakeCaseStore(log);
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> this.punishments.all().stream().filter(p -> args[0].equals(p.getShortId())).toList())
                    .build();
    private final BanIdTestBed banIds = new BanIdTestBed(this.punishments.repository());
    private final CaseConversationService service = new CaseConversationService(
            this.punishments.repository(), this.store, this.store, this.log, this.events, this.clock, this.banIds.service, new net.onelitefeather.blackhole.backend.support.RecordingProtocols());
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final SnapshotLogAppender appender = new SnapshotLogAppender();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        this.previousLevel = this.root.getLevel();
        this.root.setLevel(Level.ALL);
        this.appender.start();
        this.root.addAppender(this.appender);
    }

    @AfterEach
    void releaseLogs() {
        this.root.detachAppender(this.appender);
        this.root.setLevel(this.previousLevel);
    }

    @Test
    void messagesLeakReissueAndExportNeverPutTheBanIdInLogsEventsTheTimelineOrTheFile() {
        this.punishments.given(new PunishmentEntity(ID, UUID.randomUUID(), "ab".repeat(64), "network", false, 1L, null, new HashMap<>()), ID);
        String banId = this.banIds.issueFor(ID);
        this.store.ensureCase(ID, 1L);

        this.service.postMessage(ID, new CaseConversationDTO.MessageRequest("we will look into it", null), ADMIN, "k-1");
        this.service.postMessage(ID, new CaseConversationDTO.MessageRequest("my id is #" + banId, null), ADMIN, null);
        this.service.markBanIdLeaked(ID, new CaseConversationDTO.LeakRequest(BanIdLeakReason.POSTED_PUBLIC), ADMIN);
        this.store.collideNext(1);
        CaseConversationDTO reissued = this.service.reissueBanId(ID, new CaseConversationDTO.ReissueRequest(BanIdReissueReason.OTHER), ADMIN);
        CaseConversationDTO.Transcript json = assertInstanceOf(CaseConversationDTO.Transcript.class, this.service.exportTranscript(ID, "json", true, ADMIN));
        CaseConversationDTO.Transcript text = assertInstanceOf(CaseConversationDTO.Transcript.class, this.service.exportTranscript(ID, "text", true, ADMIN));

        assertInstanceOf(CaseConversationDTO.ReissueResponse.class, reissued);
        assertFalse(this.appender.snapshot().isEmpty(), "precondition: the flows log something (the collision retry)");
        for (ILoggingEvent line : this.appender.snapshot()) {
            assertNoBanId(banId, line.getFormattedMessage());
            assertNoBanId(banId, String.valueOf(line.getThrowableProxy()));
        }
        assertTrue(this.events.events().size() >= 3, "precondition: events were published");
        this.events.events().forEach(event -> assertNoBanId(banId, event.type() + event.payload()));
        this.log.all().forEach(event -> assertNoBanId(banId, event.type() + event.body()));
        assertNoBanId(banId, json.content());
        assertNoBanId(banId, text.content());
        assertNoBanId(banId, String.valueOf(reissued));
    }

    private static void assertNoBanId(String banId, String text) {
        assertFalse(text.contains(banId), "the Ban ID must not appear in: " + text.replace(banId, "<ban id>"));
    }
}
