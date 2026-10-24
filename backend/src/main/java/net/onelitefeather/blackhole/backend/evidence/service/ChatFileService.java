package net.onelitefeather.blackhole.backend.evidence.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.casetimeline.ChatFiles;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.ChatAttachmentConfig;
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
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobKeys;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobStore;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadPipeline;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadProfile;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadSpooler;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The files of the appeal chat. An upload is <b>staged</b>: scanned first, sniffed, stored and written as a pending evidence item with
 * the correlation id {@code chat:<scope>} and no punishment, so it is in no listing, count, owner listing or content route and nobody
 * can fetch it. A message <b>binds</b> it (the claim is part of the statement that records the message, see {@code CaseChanges}); only
 * then is it case evidence. An unbound file is removed by the housekeeping sweep after {@code pending-ttl}.
 *
 * <p>{@code @Transactional} is unusable here, so a blob is written before its row and deleted before it: a crash in between leaves an
 * orphan blob the housekeeping sweep removes, never a row pointing at nothing.</p>
 */
@Singleton
public class ChatFileService implements ChatFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatFileService.class);
    private static final String SOURCE_SYSTEM = "chat";

    /**
     * Where a file goes.
     *
     * @param punishmentIdentifier the punishment of the conversation
     * @param scope                the staging scope, {@link ChatFiles#appealScope} for a player or {@link ChatFiles#caseScope} for staff
     * @param provenance           who uploads
     * @param by                   the verified staff attribution; {@code null} for a player
     * @param fileName             the client's file name, used only for the sanitised display name
     */
    public record Stage(String punishmentIdentifier, String scope, EvidenceProvenance provenance, @Nullable Attribution by, @Nullable String fileName) {
    }

    private final EvidenceItemRepository items;
    private final EvidenceAuditRepository audit;
    private final PunishmentRepository punishments;
    private final UploadPipeline uploads;
    private final BlobStore blobs;
    private final DomainEventPublisher events;
    private final CaseTimeline caseTimeline;
    private final ChatAttachmentConfig config;
    private final Clock clock;
    private final Path spoolDirectory;

    @Inject
    public ChatFileService(
            EvidenceItemRepository items, EvidenceAuditRepository audit, PunishmentRepository punishments, UploadPipeline uploads,
            BlobStore blobs, DomainEventPublisher events, CaseTimeline caseTimeline, ChatAttachmentConfig config, Clock clock,
            @Value("${java.io.tmpdir}") Path spoolDirectory
    ) {
        this.items = items;
        this.audit = audit;
        this.punishments = punishments;
        this.uploads = uploads;
        this.blobs = blobs;
        this.events = events;
        this.caseTimeline = caseTimeline;
        this.config = config;
        this.clock = clock;
        this.spoolDirectory = spoolDirectory;
    }

    @Override
    public int maxPerMessage() {
        return this.config.getMaxPerMessage();
    }

    /**
     * Scans, checks and stages one file.
     *
     * @return the {@link EvidenceDTO.Staged} file, or an {@link EvidenceDTO.Error}: 404 unknown punishment, 409 {@code QUOTA}, 413 too
     * large, 415 type not allowed, 422 {@code FILE_FLAGGED} / {@code FILE_ACTIVE_CONTENT} / not valid, 503 scanner or store unavailable
     */
    public EvidenceDTO stage(Stage request, InputStream content) {
        PunishmentEntity punishment = this.punishments.findById(request.punishmentIdentifier()).orElse(null);
        if (punishment == null) {
            return error(EvidenceErrorKind.NOT_FOUND, "there is no such punishment");
        }
        if (this.items.countByCorrelationIdAndPunishmentIdentifierIsNull(request.scope()) >= this.config.getMaxStaged()) {
            return error(EvidenceErrorKind.QUOTA, "too many files are waiting to be sent; send the message or remove one");
        }
        if (this.items.countByCorrelationId(request.scope()) >= this.config.getMaxFilesPerAppeal()) {
            return error(EvidenceErrorKind.QUOTA, "this conversation has reached the limit of " + this.config.getMaxFilesPerAppeal() + " files");
        }

        Path spooled = null;
        Path processed = null;
        String key = null;
        try {
            UploadSpooler.Spooled spool = UploadSpooler.spool(content, this.spoolDirectory, this.uploads.maxUploadBytes(UploadProfile.CHAT));
            if (spool.tooLarge()) {
                return error(EvidenceErrorKind.PAYLOAD_TOO_LARGE, "files may be at most " + this.config.getMaxBytes() + " bytes");
            }
            spooled = spool.file();
            UploadPipeline.Outcome outcome = this.uploads.process(spooled, request.fileName(), UploadProfile.CHAT);
            if (outcome.error() != null) {
                return outcome.error();
            }
            UploadPipeline.Accepted accepted = outcome.accepted();
            processed = accepted.file();

            key = BlobKeys.generate(this.clock);
            try (InputStream in = Files.newInputStream(accepted.file())) {
                this.blobs.put(key, in, accepted.size(), accepted.contentType());
            }
            EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), null, punishment.getOwner(), request.scope(), EvidenceKind.FILE,
                    SOURCE_SYSTEM, this.clock.millis());
            item.setProvenance(request.provenance());
            if (request.by() != null) {
                item.setCreatedBy(request.by().source());
                item.setRecordedByOid(request.by().recordedByOid());
                item.setActorOid(request.by().actorOid());
            }
            item.setFile(key, accepted.displayName(), accepted.contentType(), accepted.size(), accepted.sha256());
            Map<String, Object> size = new LinkedHashMap<>();
            if (accepted.width() != null && accepted.height() != null) {
                size.put("width", accepted.width());
                size.put("height", accepted.height());
            }
            item.setContent(null, size.isEmpty() ? null : size);
            EvidenceItemEntity saved;
            try {
                saved = this.items.save(item);
            } catch (RuntimeException saveFailed) {
                deleteQuietly(key);
                throw saveFailed;
            }
            record(saved, EvidenceAuditAction.ATTACHED, request.by(), null);
            return new EvidenceDTO.Staged(saved.getIdentifier(), saved.getFileName(), saved.getFileContentType(), saved.getFileSize(),
                    accepted.width(), accepted.height());
        } catch (IOException e) {
            LOGGER.warn("Storing a chat file failed", e);
            if (key != null) {
                deleteQuietly(key);
            }
            return error(EvidenceErrorKind.UNAVAILABLE, "the file could not be stored");
        } finally {
            deleteTemp(spooled);
            if (processed != null && !processed.equals(spooled)) {
                deleteTemp(processed);
            }
        }
    }

    /**
     * Removes a staged file of the scope at once (the composer's remove button). Only a staged item of exactly this scope; anything else,
     * including a file a message already bound, is reported as not found.
     *
     * @return {@code true} when a staged file was deleted
     */
    public boolean unstage(String scope, UUID attachmentId) {
        EvidenceItemEntity item = this.items.findById(attachmentId).orElse(null);
        if (item == null || !item.isStagedChatFile() || !scope.equals(item.getCorrelationId())) {
            return false;
        }
        return deleteBlobAndRow(item, null, EvidenceDeletionReason.MANUAL, EvidenceDeleteReason.OTHER);
    }

    @Override
    public Resolved resolve(String punishmentIdentifier, String scope, List<UUID> attachmentIds) {
        Optional<PunishmentEntity> punishment = this.punishments.findById(punishmentIdentifier);
        if (punishment.isEmpty()) {
            return new Resolved(List.of(), "there is no such punishment");
        }
        List<File> files = new ArrayList<>();
        for (UUID id : attachmentIds) {
            EvidenceItemEntity item = this.items.findById(id).orElse(null);
            boolean usable = item != null && item.isStagedChatFile() && scope.equals(item.getCorrelationId())
                    && item.getKind() == EvidenceKind.FILE && item.getFileKey() != null
                    && punishment.get().getOwner().equals(item.getOwner());
            if (!usable) {
                // One answer for an unknown id, a used one and one that belongs to another conversation: nothing to probe.
                return new Resolved(List.of(), "an attachment is unknown, was already sent, or belongs to another conversation");
            }
            Integer width = item.getPayload() != null && item.getPayload().get("width") instanceof Number number ? number.intValue() : null;
            Integer height = item.getPayload() != null && item.getPayload().get("height") instanceof Number number ? number.intValue() : null;
            files.add(new File(item.getIdentifier(), item.getFileName(), item.getFileContentType(), item.getFileSize(), width, height,
                    item.getProvenance()));
        }
        return new Resolved(List.copyOf(files), null);
    }

    @Override
    public void bound(String punishmentIdentifier, Collection<UUID> attachmentIds) {
        for (UUID id : attachmentIds) {
            try {
                this.items.findById(id).ifPresent(item -> {
                    if (punishmentIdentifier.equals(item.getPunishmentIdentifier())) {
                        CaseActor actor = item.getProvenance() == EvidenceProvenance.PLAYER ? CaseActor.player()
                                : CaseActor.ofRow(item.getCreatedBy(), item.getRecordedByOid(), item.getActorOid());
                        this.caseTimeline.evidenceAttached(item, actor);
                    }
                });
            } catch (RuntimeException e) {
                LOGGER.warn("Could not record that a chat file became evidence of punishment {}; the reconciler will", punishmentIdentifier, e);
            }
        }
    }

    @Override
    public boolean deleteFiles(String punishmentIdentifier, Collection<UUID> attachmentIds, @Nullable Attribution by) {
        for (UUID id : attachmentIds) {
            EvidenceItemEntity item = this.items.findById(id).orElse(null);
            if (item == null || !item.isChatFile() || !punishmentIdentifier.equals(item.getPunishmentIdentifier())) {
                continue;
            }
            if (!deleteBlobAndRow(item, by, EvidenceDeletionReason.ERASURE, EvidenceDeleteReason.ERASURE_REQUEST)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean deletePlayerFiles(String punishmentIdentifier, @Nullable Attribution by) {
        for (EvidenceItemEntity item : this.items.findByPunishmentIdentifierAndProvenance(punishmentIdentifier, EvidenceProvenance.PLAYER)) {
            if (item.isChatFile() && !deleteBlobAndRow(item, by, EvidenceDeletionReason.ERASURE, EvidenceDeleteReason.ERASURE_REQUEST)) {
                return false;
            }
        }
        return true;
    }

    private boolean deleteBlobAndRow(EvidenceItemEntity item, @Nullable Attribution by, EvidenceDeletionReason published, EvidenceDeleteReason reason) {
        if (item.getFileKey() != null) {
            try {
                this.blobs.delete(item.getFileKey());
            } catch (IOException e) {
                LOGGER.warn("Deleting the file of chat evidence {} failed", item.getIdentifier(), e);
                return false;
            }
        }
        this.items.delete(item);
        record(item, EvidenceAuditAction.DELETED, by, reason.name());
        try {
            this.events.publish(EvidenceEvents.DELETED, EvidenceEvents.deleted(item, published));
        } catch (RuntimeException e) {
            LOGGER.error("Could not publish {}: {}", EvidenceEvents.DELETED, e.getMessage());
        }
        return true;
    }

    private void record(EvidenceItemEntity item, EvidenceAuditAction action, @Nullable Attribution by, @Nullable String reason) {
        EvidenceAuditEntity line = new EvidenceAuditEntity(UUID.randomUUID(), item.getIdentifier(), item.getPunishmentIdentifier(),
                item.getOwner(), action, by == null ? null : by.source(), by == null ? null : by.recordedByOid(), reason, null,
                this.clock.millis());
        line.setActorOid(by == null ? null : by.actorOid());
        this.audit.save(line);
    }

    private static EvidenceDTO.Error error(EvidenceErrorKind kind, String message) {
        return new EvidenceDTO.Error(message, kind);
    }

    private void deleteQuietly(String key) {
        try {
            this.blobs.delete(key);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not remove the orphan chat file {}; the housekeeping sweep will", key, e);
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
