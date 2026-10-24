package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDrafts;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEvents;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.evidence.EvidenceDeletionReason;
import net.onelitefeather.blackhole.backend.evidence.service.GeneratedEvidenceGateway;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates the protocol of a decided appeal from the stored case events, stores it as evidence of kind
 * {@code TRANSCRIPT} and keeps it consistent with later changes and redactions.
 *
 * <p>Generation is idempotent: the digest of the source events is computed first and compared with the
 * active version, so nothing is stored when nothing changed, and the unique indexes make concurrent runs
 * produce one version. Every step is one statement or one blob write; there is no transaction
 * ({@code @Transactional} is unusable here). Files are written before the index row and a run that loses
 * a race removes its own item again, so the worst a crash leaves is an orphan file for the housekeeping
 * sweep.</p>
 *
 * <p>No method throws. A failure is logged without content, counted in {@value #FAILURE_METER} and left for
 * the {@link ProtocolReconciler}.</p>
 */
@Singleton
public class AppealProtocolService implements AppealProtocolGenerator, ProtocolRedaction {

    /** Counts generations and purges that failed, tagged by {@code reason}. */
    public static final String FAILURE_METER = "blackhole.protocol.failures";

    /** Counts stored protocol versions. */
    public static final String STORED_METER = "blackhole.protocol.stored";

    /** The routing key of the domain event published after a version was stored. */
    public static final String STORED_EVENT = CaseEvents.routingKey("protocol.stored");

    private static final Logger LOGGER = LoggerFactory.getLogger(AppealProtocolService.class);
    private static final int PURGE_BATCH = 100;

    private final ProtocolSource source;
    private final ProtocolStore store;
    private final GeneratedEvidenceGateway evidence;
    private final CaseTimeline timeline;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Duration settleDelay;
    private final int attempts;
    private final Counter stored;

    public AppealProtocolService(
            ProtocolSource source,
            ProtocolStore store,
            GeneratedEvidenceGateway evidence,
            CaseTimeline timeline,
            DomainEventPublisher events,
            Clock clock,
            MeterRegistry meters,
            @Value("${blackhole.protocol.settle-delay:PT1M}") Duration settleDelay,
            @Value("${blackhole.protocol.retries:3}") int attempts
    ) {
        this.source = source;
        this.store = store;
        this.evidence = evidence;
        this.timeline = timeline;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
        this.settleDelay = settleDelay;
        this.attempts = Math.max(1, attempts);
        this.stored = Counter.builder(STORED_METER).description("Stored appeal protocol versions").register(meters);
    }

    @Override
    public Result generate(UUID appealId, boolean force) {
        try {
            return attempt(appealId, force);
        } catch (RuntimeException e) {
            failed("error");
            LOGGER.warn("Generating the protocol of appeal {} failed; the reconciler will retry", appealId, e);
            return Result.of(Outcome.FAILED);
        }
    }

    @Override
    public boolean isCurrent(UUID appealId) {
        try {
            ProtocolSource.Appeal appeal = this.source.appeal(appealId).orElse(null);
            if (appeal == null || !appeal.decided()) {
                return false;
            }
            ProtocolVersion active = this.store.active(appealId).orElse(null);
            if (active == null) {
                return false;
            }
            ProtocolModel model = ProtocolModel.build(appeal, this.source.appealsOf(appeal.punishmentIdentifier()),
                    this.source.entries(appeal.punishmentIdentifier()), this.source.fileDigests(appeal.punishmentIdentifier()));
            return !model.ticketIncomplete() && active.sourceDigest().equals(model.digest());
        } catch (RuntimeException e) {
            failed("error");
            LOGGER.warn("Could not check whether the protocol of appeal {} is current", appealId, e);
            return false;
        }
    }

    private Result attempt(UUID appealId, boolean force) {
        ProtocolSource.Appeal appeal = this.source.appeal(appealId).orElse(null);
        if (appeal == null) {
            return Result.of(Outcome.NOT_FOUND);
        }
        if (!appeal.decided()) {
            return Result.of(Outcome.NOT_DECIDED);
        }
        for (int attempt = 0; attempt < this.attempts; attempt++) {
            this.store.settleLostEvidence(appealId, this.clock.millis());
            ProtocolModel model = ProtocolModel.build(appeal, this.source.appealsOf(appeal.punishmentIdentifier()),
                    this.source.entries(appeal.punishmentIdentifier()), this.source.fileDigests(appeal.punishmentIdentifier()));
            if (model.ticketIncomplete()) {
                return Result.of(Outcome.TICKET_INCOMPLETE);
            }
            ProtocolVersion active = this.store.active(appealId).orElse(null);
            if (active != null && active.sourceDigest().equals(model.digest())) {
                return unchanged(active);
            }
            long now = this.clock.millis();
            long quietSince = Math.max(model.lastActivityAt(), appeal.decidedAt());
            if (!force && now - quietSince < this.settleDelay.toMillis()) {
                return Result.of(Outcome.NOT_SETTLED);
            }
            Result result = store(appeal, model, now);
            if (result != null) {
                return result;
            }
        }
        failed("conflict");
        LOGGER.warn("The protocol of appeal {} kept changing while it was stored; the reconciler will retry", appealId);
        return Result.of(Outcome.FAILED);
    }

    /**
     * Stores one new version. Returns {@code null} when the version number was taken or an identical version
     * appeared in the meantime and the caller should look again.
     */
    private @Nullable Result store(ProtocolSource.Appeal appeal, ProtocolModel model, long now) {
        int version = this.store.nextVersion(appeal.identifier());
        ProtocolRenderer.Rendered rendered = ProtocolRenderer.render(model, version, now);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("appealId", appeal.identifier().toString());
        metadata.put("version", version);
        metadata.put("entries", model.entryCount());
        metadata.put("digest", model.digest());
        GeneratedEvidenceGateway.Stored item;
        try {
            item = this.evidence.store(appeal.punishmentIdentifier(), "appeal-protocol-" + appeal.identifier() + "-v" + version,
                    rendered.text(), rendered.json(), metadata);
        } catch (GeneratedEvidenceGateway.StoreException e) {
            failed(e.tooLarge() ? "too_large" : "storage");
            LOGGER.warn("Storing the protocol of appeal {} failed ({}); the reconciler will retry", appeal.identifier(),
                    e.tooLarge() ? "larger than blackhole.protocol.max-bytes" : "storage error", e.getCause());
            return Result.of(e.tooLarge() ? Outcome.TOO_LARGE : Outcome.FAILED);
        }
        boolean added;
        try {
            added = this.store.add(new ProtocolStore.NewVersion(UUID.randomUUID(), appeal.punishmentIdentifier(), appeal.identifier(), version,
                    item.evidenceId(), model.digest(), model.entryCount(), model.firstSeq(), model.lastSeq(), now));
        } catch (RuntimeException e) {
            dropQuietly(item.evidenceId());
            throw e;
        }
        if (!added) {
            dropQuietly(item.evidenceId());
            ProtocolVersion active = this.store.active(appeal.identifier()).orElse(null);
            return active != null && active.sourceDigest().equals(model.digest()) ? unchanged(active) : null;
        }
        this.stored.increment();
        recordStored(appeal, version, item.evidenceId(), model, now);
        return new Result(Outcome.STORED, version, item.evidenceId(), model.entryCount());
    }

    private static Result unchanged(ProtocolVersion active) {
        return new Result(Outcome.UNCHANGED, active.version(), active.evidenceId(), active.entryCount());
    }

    /** The timeline event and the domain event; both after the write, neither can fail it. */
    private void recordStored(ProtocolSource.Appeal appeal, int version, UUID evidenceId, ProtocolModel model, long now) {
        this.timeline.record(CaseDrafts.protocolStored(appeal.punishmentIdentifier(), appeal.identifier().toString(), version,
                evidenceId.toString(), model.entryCount(), model.digest(), now));
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("appealId", appeal.identifier().toString());
            payload.put("version", version);
            payload.put("evidenceId", evidenceId.toString());
            payload.put("entries", model.entryCount());
            payload.put("punishmentIdentifier", appeal.punishmentIdentifier());
            payload.put("owner", appeal.owner());
            payload.put("occurredAt", now);
            this.events.publish(STORED_EVENT, payload);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish {} for appeal {}", STORED_EVENT, appeal.identifier(), e);
        }
    }

    private void dropQuietly(UUID evidenceId) {
        try {
            this.evidence.delete(evidenceId, EvidenceDeletionReason.DUPLICATE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not remove the duplicate protocol evidence {}; the housekeeping sweep will remove its files", evidenceId, e);
        }
    }

    // ---- redaction ----

    @Override
    public void afterRedaction(String punishmentIdentifier) {
        regenerate(punishmentIdentifier);
    }

    @Override
    public void afterParticipantErased(String punishmentIdentifier) {
        try {
            this.store.markPurgePending(punishmentIdentifier);
        } catch (RuntimeException e) {
            failed("mark");
            LOGGER.warn("Could not mark the protocols of punishment {} for purging; the redaction stays", punishmentIdentifier, e);
            return;
        }
        regenerate(punishmentIdentifier);
    }

    @Override
    public void afterStaffAnonymized(UUID actorOid) {
        List<String> punishments;
        try {
            punishments = this.store.markPurgePendingForActor(actorOid);
        } catch (RuntimeException e) {
            failed("mark");
            LOGGER.warn("Could not mark the protocols of a staff actor for purging; repeat the anonymisation to retry", e);
            return;
        }
        punishments.forEach(this::regenerate);
    }

    /** Deletes the purged files, then stores a fresh version with the markers; neither step undoes the redaction. */
    private void regenerate(String punishmentIdentifier) {
        try {
            purgePending();
            for (ProtocolSource.Appeal appeal : this.source.appealsOf(punishmentIdentifier)) {
                if (appeal.decided()) {
                    generate(appeal.identifier(), true);
                }
            }
        } catch (RuntimeException e) {
            failed("regenerate");
            LOGGER.warn("Regenerating the protocols of punishment {} after a redaction failed; the reconciler will retry", punishmentIdentifier, e);
        }
    }

    /**
     * Deletes the files of every version a redaction marked and tombstones its row. A file that cannot be
     * deleted leaves its version unreadable and pending for the next run.
     *
     * @return how many versions were purged
     */
    public int purgePending() {
        int purged = 0;
        for (ProtocolVersion version : this.store.purgePending(PURGE_BATCH)) {
            try {
                if (version.evidenceId() != null) {
                    this.evidence.delete(version.evidenceId(), EvidenceDeletionReason.ERASURE);
                }
                if (this.store.markPurged(version.identifier(), this.clock.millis())) {
                    purged++;
                }
            } catch (IOException | RuntimeException e) {
                failed("purge");
                LOGGER.warn("Could not purge protocol version {} of appeal {}; it stays unreadable and is retried", version.version(),
                        version.appealId(), e);
            }
        }
        return purged;
    }

    private void failed(String reason) {
        Counter.builder(FAILURE_METER).description("Appeal protocol work that failed").tag("reason", reason).register(this.meters).increment();
    }
}
