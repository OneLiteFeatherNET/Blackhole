package net.onelitefeather.blackhole.backend.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;

import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.elo.EloEventEntity;
import net.onelitefeather.blackhole.backend.elo.EloEventRepository;
import net.onelitefeather.blackhole.backend.elo.EloProfileCache;
import net.onelitefeather.blackhole.backend.elo.EloScoreEntity;
import net.onelitefeather.blackhole.backend.elo.EloScoreId;
import net.onelitefeather.blackhole.backend.elo.EloScoreRepository;
import net.onelitefeather.blackhole.backend.elo.EscalationPolicy;
import net.onelitefeather.blackhole.backend.elo.ThresholdEscalationPolicy;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.SectionCache;
import net.onelitefeather.blackhole.backend.section.SectionEntity;
import net.onelitefeather.blackhole.backend.section.SectionRepository;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.SectionSpec;

import java.time.Duration;
import java.util.List;

/**
 * A complete {@link EloService} over in-memory repositories, a fixed clock and recording
 * collaborators, with the two default sections ({@code chat} MUTE and {@code network} BAN, base 1000,
 * soft 700, permanent 300, pardon 700, decay 10 per day). Every test builds its own.
 */
public final class EloTestBed {

    public final InMemorySharedCache sharedCache = new InMemorySharedCache();
    public final TestClock clock = new TestClock();
    public final RecordingEventPublisher events = new RecordingEventPublisher();
    public final RecordingSectionPunisher punisher = new RecordingSectionPunisher();
    public final InMemoryRepository<SectionRepository, SectionEntity, String> sectionRepository =
            InMemoryRepository.<SectionRepository, SectionEntity, String>of(SectionRepository.class, SectionEntity::getSectionKey).build();
    public final InMemoryRepository<EloScoreRepository, EloScoreEntity, EloScoreId> scoreRepository =
            InMemoryRepository.<EloScoreRepository, EloScoreEntity, EloScoreId>of(EloScoreRepository.class, EloScoreEntity::getId)
                    .finder("findByIdOwner", args -> this.scoreRepository.all().stream()
                            .filter(score -> score.getOwner().equals(args[0])).toList())
                    .build();
    public final InMemoryRepository<EloEventRepository, EloEventEntity, Object> eventRepository =
            InMemoryRepository.<EloEventRepository, EloEventEntity, Object>of(EloEventRepository.class, event -> event).build();
    public final SectionService sections = new SectionService(
            sectionRepository.repository(), new SectionCache(sharedCache, Duration.ofMinutes(5)), events, key -> { },
            new StaffAuditRecorder(new InMemoryStaffAuditStore(), new DomainEventPublisher(new RecordingEventTransport(), new TestClock()),
                    new AuditPayloadGuard(), new TestClock(), new SimpleMeterRegistry()));
    public final EloProfileCache profileCache = new EloProfileCache(sharedCache, Duration.ofSeconds(30));
    public final EloService elo;

    public EloTestBed() {
        this(new ThresholdEscalationPolicy());
    }

    public EloTestBed(EscalationPolicy policy) {
        addSection("chat", EnforcementKind.MUTE, "PT24H");
        addSection("network", EnforcementKind.BAN, "P3D");
        this.elo = new EloService(
                scoreRepository.repository(), new InMemoryEloScoreWriter(scoreRepository.repository()), eventRepository.repository(), sections, punisher, policy,
                events, profileCache, clock, 86_400_000L,
                new StaffAuditRecorder(new InMemoryStaffAuditStore(), new DomainEventPublisher(new RecordingEventTransport(), clock),
                        new AuditPayloadGuard(), clock, new SimpleMeterRegistry()));
    }

    /** Adds an enabled section with the default numbers. */
    public void addSection(String key, EnforcementKind enforcement, String softDuration) {
        this.sectionRepository.repository().save(new SectionEntity(new SectionSpec(
                key, key, enforcement, enforcement == EnforcementKind.SERVER ? List.of(key + "-*") : List.of(),
                1000, 700, softDuration, 300, 700, "permanent " + key, 10), true));
    }

    /** Stores a score as if the owner had been scored earlier. */
    public void givenScore(String owner, String section, int score) {
        this.scoreRepository.repository().save(new EloScoreEntity(owner, section, score, this.clock.millis()));
    }

    /** The stored score, or -1 if the owner has no row in the section. */
    public int storedScore(String owner, String section) {
        return this.scoreRepository.repository().findById(new EloScoreId(owner, section))
                .map(EloScoreEntity::getScore).orElse(-1);
    }
}
