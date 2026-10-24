package net.onelitefeather.blackhole.backend.evidence.service;

import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import io.micronaut.context.annotation.Value;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditRepository;
import net.onelitefeather.blackhole.backend.evidence.EvidenceDeleteReason;
import net.onelitefeather.blackhole.backend.evidence.EvidenceDeletionReason;
import net.onelitefeather.blackhole.backend.evidence.EvidenceEvents;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemRepository;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobKeys;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobStore;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadPipeline;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadSpooler;
import net.onelitefeather.blackhole.backend.evidence.validation.PayloadValidator;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Attaches, lists, reads and deletes evidence. Expected failures come back as
 * {@link EvidenceDTO.Error}, never as exceptions.
 *
 * <p>Evidence belongs to a punishment, or - while it waits for one - to an owner and a client
 * chosen correlation id. A punishment claims the pending items of its owner when it is created
 * ({@link #claimPending}). Evidence is never deleted because of time: only with its punishment,
 * by an admin with a reason, or (unclaimed) by the housekeeping sweep.</p>
 *
 * <p>{@code @Transactional} is unusable in this codebase, so a blob is always written before its
 * row and deleted before it: a crash in between leaves an orphan blob the housekeeping sweep
 * removes, never a row pointing at nothing.</p>
 */
@Singleton
public class EvidenceService implements PunishmentEvidenceGateway {

    /** The correlation id prefix of pending evidence that a report references. */
    public static final String REPORT_CORRELATION_PREFIX = "report:";

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceService.class);

    /** The longest source system, note and description the API accepts. */
    private static final int MAX_SOURCE_SYSTEM = 64;

    /** The answer to an {@code onBehalfOf} from any role but the proxy. */
    static final String ON_BEHALF_OF_PROXY_ONLY = "onBehalfOf is only accepted from the proxy";
    private static final int MAX_NOTE = 500;
    private static final int MAX_DESCRIPTION = 500;

    private final EvidenceItemRepository items;
    private final EvidenceAuditRepository audit;
    private final PunishmentRepository punishments;
    private final PayloadValidator validator;
    private final UploadPipeline uploads;
    private final BlobStore blobs;
    private final DomainEventPublisher events;
    private final StaffLinkService staffLinks;
    private final CaseTimeline caseTimeline;
    private final TranscriptAccess transcripts;
    private final Clock clock;
    private final Path spoolDirectory;

    /**
     * @param spoolDirectory where uploads are spooled before they are checked; the JVM's temp directory by default
     */
    public EvidenceService(
            EvidenceItemRepository items,
            EvidenceAuditRepository audit,
            PunishmentRepository punishments,
            PayloadValidator validator,
            UploadPipeline uploads,
            BlobStore blobs,
            DomainEventPublisher events,
            StaffLinkService staffLinks,
            CaseTimeline caseTimeline,
            TranscriptAccess transcripts,
            Clock clock,
            @Value("${java.io.tmpdir}") Path spoolDirectory
    ) {
        this.items = items;
        this.audit = audit;
        this.punishments = punishments;
        this.validator = validator;
        this.uploads = uploads;
        this.blobs = blobs;
        this.events = events;
        this.staffLinks = staffLinks;
        this.caseTimeline = caseTimeline;
        this.transcripts = transcripts;
        this.clock = clock;
        this.spoolDirectory = spoolDirectory;
    }

    /** Where evidence is attached: an existing punishment, or an owner's pending correlation. */
    private record Target(String punishmentIdentifier, String owner, String correlationId, EvidenceDTO.Error error) {

        static Target failed(EvidenceErrorKind kind, String message) {
            return new Target(null, null, null, new EvidenceDTO.Error(message, kind));
        }
    }

    private record Found(PunishmentEntity punishment, EvidenceDTO.Error error) {
    }

    /**
     * Attaches structured or link evidence.
     *
     * @return the {@link EvidenceDTO.Response}, or an {@link EvidenceDTO.Error}: 400 for an invalid
     * request or payload, 404/409 for an unknown or ambiguous punishment, 413 for an oversized
     * payload, 422 for personal data in the payload
     */
    public EvidenceDTO attach(EvidenceDTO.CreateRequest request, Actor actor) {
        if (request.kind() == EvidenceKind.TRANSCRIPT) {
            return error(EvidenceErrorKind.VALIDATION, "TRANSCRIPT evidence is generated by the system and cannot be attached");
        }
        EvidenceDTO.Error invalid = checkCommon(request.sourceSystem());
        if (invalid != null) {
            return invalid;
        }
        Attribution attribution = actor.attribution(request.onBehalfOf()).map(this.staffLinks::withLinkedActor).orElse(null);
        if (attribution == null) {
            return error(EvidenceErrorKind.VALIDATION, ON_BEHALF_OF_PROXY_ONLY);
        }
        Target target = resolveTarget(request.punishment(), request.owner(), request.correlationId());
        if (target.error() != null) {
            return target.error();
        }
        PayloadValidator.Validated validated = this.validator.validate(request.kind(), request.reference(), request.payload());
        if (!validated.accepted()) {
            return validated.error();
        }
        EvidenceItemEntity item = newItem(target, request.kind(), request.sourceSystem(), request.capturedAt(), attribution);
        item.setContent(validated.reference(), validated.payload());
        return attached(this.items.save(item), attribution);
    }

    /**
     * Attaches an uploaded file. The body is spooled to a bounded temporary file, checked by the
     * {@link UploadPipeline} and only then stored; whatever fails leaves neither a blob nor a row.
     *
     * @param content the file part's bytes
     * @return the {@link EvidenceDTO.Response}, or an {@link EvidenceDTO.Error}: 400, 404/409 for
     * the target, 413 too large, 415 type not allowed, 422 infected or not a valid image, 503 when
     * the scanner or the file store is unavailable
     */
    public EvidenceDTO attachFile(EvidenceDTO.UploadRequest request, InputStream content, Actor actor) {
        EvidenceDTO.Error invalid = checkCommon(request.sourceSystem());
        if (invalid != null) {
            return invalid;
        }
        Attribution attribution = actor.attribution(request.onBehalfOf()).map(this.staffLinks::withLinkedActor).orElse(null);
        if (attribution == null) {
            return error(EvidenceErrorKind.VALIDATION, ON_BEHALF_OF_PROXY_ONLY);
        }
        Target target = resolveTarget(request.punishment(), request.owner(), request.correlationId());
        if (target.error() != null) {
            return target.error();
        }
        Map<String, Object> description = null;
        if (request.description() != null && !request.description().isBlank()) {
            if (request.description().length() > MAX_DESCRIPTION) {
                return error(EvidenceErrorKind.VALIDATION, "description may be at most " + MAX_DESCRIPTION + " characters");
            }
            Optional<String> pii = PiiGuard.firstViolation("/description", request.description());
            if (pii.isPresent()) {
                return error(EvidenceErrorKind.UNPROCESSABLE, pii.get());
            }
            description = Map.of("description", request.description());
        }

        Path spooled = null;
        Path processed = null;
        String key = null;
        try {
            UploadSpooler.Spooled spool = UploadSpooler.spool(content, this.spoolDirectory, this.uploads.maxUploadBytes());
            if (spool.tooLarge()) {
                return error(EvidenceErrorKind.PAYLOAD_TOO_LARGE, "the file is larger than the largest allowed upload");
            }
            spooled = spool.file();
            UploadPipeline.Outcome outcome = this.uploads.process(spooled, request.fileName());
            if (outcome.error() != null) {
                return outcome.error();
            }
            UploadPipeline.Accepted accepted = outcome.accepted();
            processed = accepted.file();

            key = BlobKeys.generate(this.clock);
            try (InputStream in = Files.newInputStream(accepted.file())) {
                this.blobs.put(key, in, accepted.size(), accepted.contentType());
            }
            EvidenceItemEntity item = newItem(target, EvidenceKind.FILE, request.sourceSystem(), request.capturedAt(), attribution);
            item.setFile(key, accepted.displayName(), accepted.contentType(), accepted.size(), accepted.sha256());
            item.setContent(null, description);
            try {
                return attached(this.items.save(item), attribution);
            } catch (RuntimeException saveFailed) {
                deleteQuietly(key);
                throw saveFailed;
            }
        } catch (IOException e) {
            LOGGER.warn("Storing an evidence file failed", e);
            if (key != null) {
                deleteQuietly(key);
            }
            return error(EvidenceErrorKind.UNAVAILABLE, "the evidence file could not be stored");
        } finally {
            deleteTemp(spooled);
            if (processed != null && !processed.equals(spooled)) {
                deleteTemp(processed);
            }
        }
    }

    /**
     * Lists the evidence of a punishment, oldest first.
     *
     * @param includeInactive whether {@code LIFTED} and {@code REVOKED} items are returned too
     */
    public EvidenceDTO list(String punishmentReference, boolean includeInactive, Pageable pageable, Actor actor) {
        Found found = find(punishmentReference);
        if (found.error() != null) {
            return found.error();
        }
        String id = found.punishment().getIdentifier();
        Page<EvidenceItemEntity> page = includeInactive
                ? this.items.findByPunishmentIdentifierOrderByCreatedAt(id, pageable)
                : this.items.findByPunishmentIdentifierAndStatusOrderByCreatedAt(id, EvidenceStatus.VALID, pageable);
        auditInactive(page, actor.attribution());
        return toPage(page);
    }

    /** Lists the evidence of an owner hash, oldest first (access requests). */
    public EvidenceDTO listByOwner(String owner, boolean includeInactive, Pageable pageable, Actor actor) {
        if (!OwnerHash.isValid(owner)) {
            return error(EvidenceErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        Page<EvidenceItemEntity> page = includeInactive
                ? this.items.findByOwnerOrderByCreatedAt(owner, pageable)
                : this.items.findByOwnerAndStatusOrderByCreatedAt(owner, EvidenceStatus.VALID, pageable);
        // A chat file nobody has sent yet is not evidence of anything: it appears here once a message binds it.
        Page<EvidenceItemEntity> visible = Page.of(page.getContent().stream().filter(item -> !item.isStagedChatFile()).toList(),
                pageable, page.getTotalSize());
        auditInactive(visible, actor.attribution());
        return toPage(visible);
    }

    /** The one item with its metadata and payload; reading it is audited, as {@code VIEWED_INACTIVE} when it is lifted or revoked. */
    public EvidenceDTO get(UUID identifier, Actor actor) {
        EvidenceItemEntity item = this.items.findById(identifier).orElse(null);
        if (item == null || unreadableTranscript(item) || item.isStagedChatFile()) {
            return notFound(identifier);
        }
        record(item, item.getStatus() == EvidenceStatus.VALID ? EvidenceAuditAction.VIEWED : EvidenceAuditAction.VIEWED_INACTIVE, actor.attribution(), null, null);
        return toResponse(item);
    }

    /** The number of valid items of a punishment - the only thing a player may learn. */
    public EvidenceDTO count(String punishmentReference) {
        Found found = find(punishmentReference);
        if (found.error() != null) {
            return found.error();
        }
        String identifier = found.punishment().getIdentifier();
        return new EvidenceDTO.CountResponse(countValid(identifier), countPlayerSupplied(identifier));
    }

    /**
     * The team evidence: valid items with staff provenance, the protocol excluded. This is the number the ban screen and the
     * punishment DTOs show, so a file the banned player uploaded themselves cannot change it.
     */
    @Override
    public long countValid(String punishmentIdentifier) {
        return this.items.countByPunishmentIdentifierAndStatusAndKindNotEqualAndProvenance(punishmentIdentifier, EvidenceStatus.VALID,
                EvidenceKind.TRANSCRIPT, EvidenceProvenance.STAFF);
    }

    /** The valid files the appealing player sent in the chat: a separate figure, labelled player-supplied and unverified. */
    @Override
    public long countPlayerSupplied(String punishmentIdentifier) {
        return this.items.countByPunishmentIdentifierAndStatusAndProvenance(punishmentIdentifier, EvidenceStatus.VALID, EvidenceProvenance.PLAYER);
    }

    /**
     * Opens the stored file of an item.
     *
     * @param rangeHeader the raw {@code Range} header, may be {@code null}
     * @param actor       the verified caller downloading; a {@code DOWNLOADED} audit entry is written when the
     *                    response starts at byte 0, so scrubbing a video does not flood the trail with one entry per range
     * @return a {@link EvidenceDTO.Download}, {@link EvidenceDTO.OutOfRange} for an unsatisfiable
     * range, or an {@link EvidenceDTO.Error} (404 when the item or its file does not exist, 503 when the store fails)
     */
    public EvidenceDTO content(UUID identifier, String rangeHeader, Actor actor) {
        EvidenceItemEntity item = this.items.findById(identifier).orElse(null);
        if (item == null || unreadableTranscript(item) || item.isStagedChatFile()) {
            return notFound(identifier);
        }
        if (item.getFileKey() == null) {
            return error(EvidenceErrorKind.NOT_FOUND, "this evidence item has no file");
        }
        long total = item.getFileSize();
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        ByteRange range = ByteRange.parse(rangeHeader, total);
        if (range instanceof ByteRange.Unsatisfiable) {
            return new EvidenceDTO.OutOfRange(total);
        }
        if (range instanceof ByteRange.Partial slice) {
            start = slice.start();
            end = slice.end();
            partial = true;
        }
        try {
            BlobStore.BlobContent blob = this.blobs.open(item.getFileKey(), start, end);
            if (start == 0) {
                record(item, EvidenceAuditAction.DOWNLOADED, actor.attribution(), null, null);
            }
            return new EvidenceDTO.Download(blob.stream(), blob.length(), start, end, total, partial,
                    item.getFileContentType(), item.getFileName());
        } catch (BlobStore.NoSuchBlobException e) {
            LOGGER.error("Evidence {} points at a missing file", identifier);
            return error(EvidenceErrorKind.NOT_FOUND, "the stored file is missing");
        } catch (IOException e) {
            LOGGER.warn("Reading the file of evidence {} failed", identifier, e);
            return error(EvidenceErrorKind.UNAVAILABLE, "the evidence file could not be read");
        }
    }

    /**
     * Deletes one item and its file.
     *
     * @param reason the admin's reason, one of {@link EvidenceDeleteReason}; required
     * @param note   an optional free-text note
     * @return the {@link EvidenceDTO.DeleteResponse}, or an {@link EvidenceDTO.Error}: 400 without
     * a valid reason (nothing is deleted), 404, 503 when the file cannot be deleted
     */
    public EvidenceDTO delete(UUID identifier, String reason, String note, Actor actor) {
        Deletion deletion = checkDeletion(reason, note);
        if (deletion.error() != null) {
            return deletion.error();
        }
        EvidenceItemEntity item = this.items.findById(identifier).orElse(null);
        if (item == null) {
            return notFound(identifier);
        }
        EvidenceDTO.Error failure = remove(item, deletion.reason(), EvidenceDeletionReason.MANUAL, note, actor.attribution());
        return failure != null ? failure : new EvidenceDTO.DeleteResponse(1);
    }

    /**
     * Deletes every item of an owner hash and their files (data-subject erasure). Stops at the
     * first file that cannot be deleted; what was deleted before stays deleted.
     */
    public EvidenceDTO deleteByOwner(String owner, String reason, String note, Actor actor) {
        Deletion deletion = checkDeletion(reason, note);
        if (deletion.error() != null) {
            return deletion.error();
        }
        if (!OwnerHash.isValid(owner)) {
            return error(EvidenceErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        int deleted = 0;
        for (EvidenceItemEntity item : this.items.findByOwner(owner)) {
            EvidenceDTO.Error failure = remove(item, deletion.reason(), EvidenceDeletionReason.ERASURE, note, actor.attribution());
            if (failure != null) {
                return failure;
            }
            deleted++;
        }
        return new EvidenceDTO.DeleteResponse(deleted);
    }

    /**
     * Attaches the owner's pending items with this correlation id to a punishment that was just
     * created. Called by the punishment service; a claim publishes no event.
     *
     * @return how many items were claimed
     */
    @Override
    public int claimPending(String owner, String correlationId, String punishmentIdentifier) {
        if (correlationId == null || correlationId.isBlank()) {
            return 0;
        }
        List<EvidenceItemEntity> pending = this.items.findByOwnerAndCorrelationIdAndPunishmentIdentifierIsNull(owner, correlationId);
        for (EvidenceItemEntity item : pending) {
            item.claim(punishmentIdentifier);
            this.items.update(item);
            this.caseTimeline.evidenceAttached(item, CaseActor.ofRow(item.getCreatedBy(), item.getRecordedByOid(), item.getActorOid()));
        }
        return pending.size();
    }

    @Override
    public int claimPendingByIds(String owner, java.util.Collection<UUID> evidenceIds, String punishmentIdentifier) {
        int claimed = 0;
        for (UUID id : evidenceIds) {
            EvidenceItemEntity item = this.items.findById(id).orElse(null);
            if (item == null || !item.isPending() || !owner.equals(item.getOwner())) {
                continue;
            }
            item.claim(punishmentIdentifier);
            this.items.update(item);
            this.caseTimeline.evidenceAttached(item, CaseActor.ofRow(item.getCreatedBy(), item.getRecordedByOid(), item.getActorOid()));
            claimed++;
        }
        return claimed;
    }

    /**
     * Marks the punishment's valid items {@code REVOKED}: staff revoked the punishment, so the
     * evidence no longer supports a current one. Nothing is deleted; items that are already
     * {@code LIFTED} or {@code REVOKED} keep their first status.
     */
    @Override
    public int markRevoked(String punishmentIdentifier, Attribution by) {
        return changeStatus(punishmentIdentifier, EvidenceStatus.REVOKED, null, by);
    }

    /**
     * Marks the punishment's valid items {@code LIFTED} by the appeal that fully lifted the
     * punishment. Nothing is deleted. Appeals that deny or only reduce a punishment must not call this.
     */
    @Override
    public int markLifted(String punishmentIdentifier, UUID appealIdentifier, Attribution by) {
        return changeStatus(punishmentIdentifier, EvidenceStatus.LIFTED, appealIdentifier, by);
    }

    /**
     * Deletes every item of a punishment together with its file (reason {@code CASCADE}); the
     * punishment record is about to be deleted. The rows would go with the foreign key anyway, but the
     * files would not. Unlike an admin delete this does not stop at a file that cannot be deleted: the
     * row goes, and the housekeeping sweep removes the orphaned file later.
     */
    @Override
    public int deleteForPunishment(String punishmentIdentifier) {
        List<EvidenceItemEntity> found = this.items.findByPunishmentIdentifier(punishmentIdentifier);
        for (EvidenceItemEntity item : found) {
            if (item.getFileKey() != null) {
                deleteQuietly(item.getFileKey());
            }
            if (item.getJsonFileKey() != null) {
                deleteQuietly(item.getJsonFileKey());
            }
            this.items.delete(item);
            record(item, EvidenceAuditAction.DELETED, null, EvidenceDeletionReason.CASCADE.name(), null);
            publish(EvidenceEvents.DELETED, EvidenceEvents.deleted(item, EvidenceDeletionReason.CASCADE));
        }
        return found.size();
    }

    /**
     * Deletes the pending items created before the cutoff that no punishment claimed, with their
     * files (reason {@code UNCLAIMED_EXPIRED}). Evidence that belongs to a punishment is never
     * deleted here, however old. An item whose file cannot be deleted stays for the next sweep.
     *
     * @return how many items were deleted
     */
    public int deleteUnclaimedCreatedBefore(long cutoff) {
        return deleteUnclaimedCreatedBefore(cutoff, cutoff);
    }

    /**
     * Like {@link #deleteUnclaimedCreatedBefore(long)}, but pending items that belong to a report (their
     * correlation id starts with {@value #REPORT_CORRELATION_PREFIX}) wait until {@code reportCutoff}
     * instead, because a report can stay open for weeks before staff apply a punishment.
     */
    public int deleteUnclaimedCreatedBefore(long cutoff, long reportCutoff) {
        int deleted = 0;
        for (EvidenceItemEntity item : this.items.findByPunishmentIdentifierIsNullAndCreatedAtLessThan(cutoff)) {
            if (!item.isPending()) {
                continue;
            }
            boolean forReport = item.getCorrelationId() != null && item.getCorrelationId().startsWith(REPORT_CORRELATION_PREFIX);
            if (forReport && item.getCreatedAt() >= reportCutoff) {
                continue;
            }
            if (item.getFileKey() != null) {
                try {
                    this.blobs.delete(item.getFileKey());
                } catch (IOException e) {
                    LOGGER.warn("Deleting the file of expired evidence {} failed, retrying at the next sweep", item.getIdentifier(), e);
                    continue;
                }
            }
            this.items.delete(item);
            record(item, EvidenceAuditAction.DELETED, null, EvidenceDeletionReason.UNCLAIMED_EXPIRED.name(), null);
            publish(EvidenceEvents.DELETED, EvidenceEvents.deleted(item, EvidenceDeletionReason.UNCLAIMED_EXPIRED));
            deleted++;
        }
        return deleted;
    }

    private int changeStatus(String punishmentIdentifier, EvidenceStatus status, UUID liftedByAppeal, Attribution by) {
        int changed = 0;
        for (EvidenceItemEntity item : this.items.findByPunishmentIdentifier(punishmentIdentifier)) {
            // The protocol of a lifted or revoked punishment matters most: it is never marked.
            if (item.getStatus() != EvidenceStatus.VALID || item.getKind() == EvidenceKind.TRANSCRIPT) {
                continue;
            }
            item.markStatus(status, this.clock.millis(), liftedByAppeal);
            this.items.update(item);
            this.caseTimeline.evidenceStatusChanged(item, CaseActor.of(by));
            changed++;
        }
        return changed;
    }

    private EvidenceDTO.Error remove(EvidenceItemEntity item, EvidenceDeleteReason reason, EvidenceDeletionReason published, String note, Attribution by) {
        if (item.getFileKey() != null) {
            try {
                this.blobs.delete(item.getFileKey());
            } catch (IOException e) {
                LOGGER.warn("Deleting the file of evidence {} failed", item.getIdentifier(), e);
                return error(EvidenceErrorKind.UNAVAILABLE, "the evidence file could not be deleted");
            }
        }
        if (item.getJsonFileKey() != null) {
            try {
                this.blobs.delete(item.getJsonFileKey());
            } catch (IOException e) {
                LOGGER.warn("Deleting the second file of evidence {} failed", item.getIdentifier(), e);
                return error(EvidenceErrorKind.UNAVAILABLE, "the evidence file could not be deleted");
            }
        }
        this.items.delete(item);
        record(item, EvidenceAuditAction.DELETED, by, reason.name(), note);
        publish(EvidenceEvents.DELETED, EvidenceEvents.deleted(item, published));
        return null;
    }

    /** Whether the item is a protocol version a redaction made unreadable. */
    private boolean unreadableTranscript(EvidenceItemEntity item) {
        return item.getKind() == EvidenceKind.TRANSCRIPT && !this.transcripts.readable(item.getIdentifier());
    }

    private EvidenceDTO attached(EvidenceItemEntity saved, Attribution by) {
        record(saved, EvidenceAuditAction.ATTACHED, by, null, null);
        publish(EvidenceEvents.ATTACHED, EvidenceEvents.attached(saved));
        this.caseTimeline.evidenceAttached(saved, CaseActor.of(by));
        return toResponse(saved);
    }

    /**
     * Announces a change. The event bus is a side channel: a broker problem is logged and never
     * fails the request that already changed the evidence.
     */
    private void publish(String eventType, Map<String, Object> payload) {
        try {
            this.events.publish(eventType, payload);
        } catch (RuntimeException e) {
            LOGGER.error("Could not publish {}: {}", eventType, e.getMessage());
        }
    }

    /** Lifted and revoked items in a returned page were revealed to the caller: each reveal is audited. */
    private void auditInactive(Page<EvidenceItemEntity> page, Attribution by) {
        for (EvidenceItemEntity item : page.getContent()) {
            if (item.getStatus() != EvidenceStatus.VALID) {
                record(item, EvidenceAuditAction.VIEWED_INACTIVE, by, null, null);
            }
        }
    }

    /**
     * Appends an audit line. It names the item, its punishment and owner (hashes and ids) and the
     * attributed actor and the verified principal ({@code null} for system actions), plus the reason and
     * note of an admin delete - never any evidence content.
     */
    private void record(EvidenceItemEntity item, EvidenceAuditAction action, Attribution by, String reason, String note) {
        EvidenceAuditEntity line = new EvidenceAuditEntity(UUID.randomUUID(), item.getIdentifier(), item.getPunishmentIdentifier(),
                item.getOwner(), action, by == null ? null : by.source(), by == null ? null : by.recordedByOid(),
                reason, note, this.clock.millis());
        line.setActorOid(by == null ? null : by.actorOid());
        this.audit.save(line);
    }

    private EvidenceItemEntity newItem(Target target, EvidenceKind kind, String sourceSystem, Long capturedAt, Attribution by) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), target.punishmentIdentifier(), target.owner(),
                target.correlationId(), kind, sourceSystem, this.clock.millis());
        item.setCapturedAt(capturedAt);
        item.setCreatedBy(by.source());
        item.setRecordedByOid(by.recordedByOid());
        item.setActorOid(by.actorOid());
        return item;
    }

    private static EvidenceDTO.Error checkCommon(String sourceSystem) {
        if (sourceSystem == null || sourceSystem.isBlank() || sourceSystem.length() > MAX_SOURCE_SYSTEM) {
            return error(EvidenceErrorKind.VALIDATION, "sourceSystem is required and may be at most " + MAX_SOURCE_SYSTEM + " characters");
        }
        return null;
    }

    private record Deletion(EvidenceDeleteReason reason, EvidenceDTO.Error error) {
    }

    /** The admin's delete reason, or the error to answer when it is missing or invalid. */
    private static Deletion checkDeletion(String reason, String note) {
        if (reason == null || reason.isBlank()) {
            return new Deletion(null, error(EvidenceErrorKind.VALIDATION, "reason is required: ERASURE_REQUEST, WRONG_EVIDENCE or OTHER"));
        }
        EvidenceDeleteReason parsed;
        try {
            parsed = EvidenceDeleteReason.valueOf(reason.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return new Deletion(null, error(EvidenceErrorKind.VALIDATION, "reason must be ERASURE_REQUEST, WRONG_EVIDENCE or OTHER"));
        }
        if (note != null && note.length() > MAX_NOTE) {
            return new Deletion(null, error(EvidenceErrorKind.VALIDATION, "note may be at most " + MAX_NOTE + " characters"));
        }
        return new Deletion(parsed, null);
    }

    private Target resolveTarget(String punishment, String owner, String correlationId) {
        boolean byPunishment = punishment != null && !punishment.isBlank();
        boolean byPending = owner != null || correlationId != null;
        if (byPunishment == byPending) {
            return Target.failed(EvidenceErrorKind.VALIDATION, "exactly one target is required: punishment, or owner together with correlationId");
        }
        if (byPunishment) {
            Found found = find(punishment);
            return found.error() != null
                    ? new Target(null, null, null, found.error())
                    : new Target(found.punishment().getIdentifier(), found.punishment().getOwner(), null, null);
        }
        if (!OwnerHash.isValid(owner)) {
            return Target.failed(EvidenceErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 64) {
            return Target.failed(EvidenceErrorKind.VALIDATION, "correlationId is required and may be at most 64 characters");
        }
        return new Target(null, owner, correlationId, null);
    }

    /** Finds a punishment by its full identifier, or by its short id when that matches exactly one. */
    private Found find(String reference) {
        if (reference == null || reference.isBlank()) {
            return new Found(null, new EvidenceDTO.Error("a punishment is required", EvidenceErrorKind.VALIDATION));
        }
        Optional<PunishmentEntity> byId = this.punishments.findById(reference.strip());
        if (byId.isPresent()) {
            return new Found(byId.get(), null);
        }
        Optional<String> key = PunishmentShortId.parse(reference);
        if (key.isEmpty()) {
            return new Found(null, new EvidenceDTO.Error("there is no such punishment", EvidenceErrorKind.NOT_FOUND));
        }
        List<PunishmentEntity> matches = this.punishments.findByShortId(key.get());
        if (matches.isEmpty()) {
            return new Found(null, new EvidenceDTO.Error("there is no punishment with the short id " + PunishmentShortId.display(key.get()), EvidenceErrorKind.NOT_FOUND));
        }
        if (matches.size() > 1) {
            String candidates = String.join(", ", matches.stream().map(PunishmentEntity::getIdentifier).toList());
            return new Found(null, new EvidenceDTO.Error("the short id " + PunishmentShortId.display(key.get())
                    + " matches several punishments, use the full identifier: " + candidates, EvidenceErrorKind.CONFLICT));
        }
        return new Found(matches.getFirst(), null);
    }

    private static EvidenceDTO toPage(Page<EvidenceItemEntity> page) {
        return new EvidenceDTO.PageResponse(
                page.getContent().stream().map(EvidenceService::toResponse).toList(),
                page.getPageNumber(), page.getSize(), page.getTotalSize(), page.getTotalPages());
    }

    static EvidenceDTO.Response toResponse(EvidenceItemEntity item) {
        EvidenceDTO.FileInfo file = item.getFileKey() == null ? null : new EvidenceDTO.FileInfo(
                item.getFileName(), item.getFileContentType(), item.getFileSize(), item.getFileSha256());
        return new EvidenceDTO.Response(item.getIdentifier(), item.getPunishmentIdentifier(), item.getOwner(), item.getCorrelationId(),
                item.getKind(), item.getSourceSystem(), item.getCapturedAt(), item.getCreatedAt(), item.getCreatedBy(),
                item.getRecordedByOid(), item.getRecordedByOid() != null, item.getActorOid(),
                item.getExternalRef(), item.getPayload() == null ? null : new HashMap<>(item.getPayload()), file,
                item.getStatus(), item.getStatusChangedAt(), item.getLiftedByAppeal(), item.getProvenance());
    }

    private static EvidenceDTO.Error notFound(UUID identifier) {
        return error(EvidenceErrorKind.NOT_FOUND, "there is no evidence " + identifier);
    }

    private static EvidenceDTO.Error error(EvidenceErrorKind kind, String message) {
        return new EvidenceDTO.Error(message, kind);
    }

    private void deleteQuietly(String key) {
        try {
            this.blobs.delete(key);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not remove the orphan evidence file {}; the housekeeping sweep will", key, e);
        }
    }

    private static void deleteTemp(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Could not delete the temporary upload {}", file, e);
        }
    }
}
