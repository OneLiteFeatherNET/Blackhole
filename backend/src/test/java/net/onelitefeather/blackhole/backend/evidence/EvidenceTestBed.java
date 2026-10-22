package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner;
import net.onelitefeather.blackhole.backend.evidence.service.EvidenceService;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadPipeline;
import net.onelitefeather.blackhole.backend.evidence.validation.PayloadValidator;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The evidence service wired over in-memory repositories and an in-memory blob store: one fixture
 * per test, a fixed clock, no database, no network, no sleeps.
 */
public final class EvidenceTestBed {

    public static final String OWNER = "ab".repeat(64);
    public static final String OTHER_OWNER = "cd".repeat(64);
    public static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());

    public final TestClock clock = new TestClock();
    public final InMemoryBlobStore blobs = new InMemoryBlobStore();
    public final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> this.punishments.all().stream()
                            .filter(p -> args[0].equals(p.getShortId()))
                            .toList())
                    .build();
    public final InMemoryRepository<EvidenceItemRepository, EvidenceItemEntity, UUID> items =
            InMemoryRepository.<EvidenceItemRepository, EvidenceItemEntity, UUID>of(EvidenceItemRepository.class, EvidenceItemEntity::getIdentifier)
                    .finder("findByPunishmentIdentifierOrderByCreatedAt", args -> page(item -> args[0].equals(item.getPunishmentIdentifier()), (Pageable) args[1]))
                    .finder("findByPunishmentIdentifierAndStatusOrderByCreatedAt", args -> page(
                            item -> args[0].equals(item.getPunishmentIdentifier()) && item.getStatus() == args[1], (Pageable) args[2]))
                    .finder("countByPunishmentIdentifierAndStatus", args -> this.items.all().stream()
                            .filter(item -> args[0].equals(item.getPunishmentIdentifier()) && item.getStatus() == args[1]).count())
                    .finder("findByPunishmentIdentifier", args -> this.items.all().stream()
                            .filter(item -> args[0].equals(item.getPunishmentIdentifier())).toList())
                    .finder("findByOwnerOrderByCreatedAt", args -> page(item -> args[0].equals(item.getOwner()), (Pageable) args[1]))
                    .finder("findByOwnerAndStatusOrderByCreatedAt", args -> page(
                            item -> args[0].equals(item.getOwner()) && item.getStatus() == args[1], (Pageable) args[2]))
                    .finder("findByOwner", args -> this.items.all().stream().filter(item -> args[0].equals(item.getOwner())).toList())
                    .finder("findByOwnerAndCorrelationIdAndPunishmentIdentifierIsNull", args -> this.items.all().stream()
                            .filter(item -> args[0].equals(item.getOwner()) && args[1].equals(item.getCorrelationId()) && item.isPending())
                            .toList())
                    .finder("findByPunishmentIdentifierIsNullAndCreatedAtLessThan", args -> this.items.all().stream()
                            .filter(item -> item.isPending() && item.getCreatedAt() < (long) args[0]).toList())
                    .finder("existsByFileKey", args -> this.items.all().stream().anyMatch(item -> args[0].equals(item.getFileKey())))
                    .build();
    public final InMemoryRepository<EvidenceAuditRepository, EvidenceAuditEntity, UUID> audit =
            InMemoryRepository.<EvidenceAuditRepository, EvidenceAuditEntity, UUID>of(EvidenceAuditRepository.class, EvidenceAuditEntity::getIdentifier)
                    .finder("findByOccurredAtLessThan", args -> this.audit.all().stream().filter(entry -> entry.getOccurredAt() < (long) args[0]).toList())
                    .build();
    public final RecordingEventPublisher events = new RecordingEventPublisher();
    public final EvidenceConfig config = new EvidenceConfig();
    public final FakeScanner scanner = new FakeScanner();
    public final EvidenceService service;
    public final EvidenceHousekeepingSweeper sweeper;

    /** A scanner a test controls. */
    public static final class FakeScanner implements EvidenceScanner {

        public ScanResult verdict = new Clean();

        @Override
        public ScanResult scan(Path file) {
            return this.verdict;
        }
    }

    public EvidenceTestBed(Path spoolDirectory) {
        this(spoolDirectory, null);
    }

    /** @param publisher the publisher the service uses instead of the recording one, or {@code null} */
    public EvidenceTestBed(Path spoolDirectory, DomainEventPublisher publisher) {
        this.service = new EvidenceService(
                items.repository(), audit.repository(), punishments.repository(), new PayloadValidator(config),
                new UploadPipeline(config, scanner), blobs, publisher == null ? events : publisher, clock, spoolDirectory);
        this.sweeper = new EvidenceHousekeepingSweeper(service, items.repository(), audit.repository(), blobs, config, clock);
    }

    private Page<EvidenceItemEntity> page(Predicate<EvidenceItemEntity> filter, Pageable pageable) {
        List<EvidenceItemEntity> matching = this.items.all().stream()
                .filter(filter).sorted(Comparator.comparingLong(EvidenceItemEntity::getCreatedAt)).toList();
        int from = (int) Math.min(pageable.getOffset(), matching.size());
        int to = (int) Math.min(from + pageable.getSize(), matching.size());
        return Page.of(matching.subList(from, to), pageable, (long) matching.size());
    }

    /** Stores a punishment of {@link #OWNER} with the given identifier. */
    public PunishmentEntity givenPunishment(String identifier) {
        PunishmentEntity punishment = new PunishmentEntity(identifier, STAFF, OWNER, "network", false, 1L, null, new HashMap<>());
        this.punishments.given(punishment, identifier);
        return punishment;
    }
}
