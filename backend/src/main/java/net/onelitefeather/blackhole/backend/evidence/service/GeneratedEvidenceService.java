package net.onelitefeather.blackhole.backend.evidence.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditRepository;
import net.onelitefeather.blackhole.backend.evidence.EvidenceDeletionReason;
import net.onelitefeather.blackhole.backend.evidence.EvidenceEvents;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemRepository;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobKeys;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobStore;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores, reads and deletes {@code TRANSCRIPT} evidence. It writes the blobs before the row and deletes
 * them before it, like {@link EvidenceService} ({@code @Transactional} is unusable here), and it audits
 * every use without content.
 */
@Singleton
public class GeneratedEvidenceService implements GeneratedEvidenceGateway {

    /** The {@code sourceSystem} of every generated item. */
    public static final String SOURCE_SYSTEM = "blackhole";

    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedEvidenceService.class);
    private static final String TEXT_EXTENSION = ".txt";

    private final EvidenceItemRepository items;
    private final EvidenceAuditRepository audit;
    private final PunishmentRepository punishments;
    private final BlobStore blobs;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final long maxBytes;

    public GeneratedEvidenceService(
            EvidenceItemRepository items,
            EvidenceAuditRepository audit,
            PunishmentRepository punishments,
            BlobStore blobs,
            DomainEventPublisher events,
            Clock clock,
            @Value("${blackhole.protocol.max-bytes:16777216}") long maxBytes
    ) {
        this.items = items;
        this.audit = audit;
        this.punishments = punishments;
        this.blobs = blobs;
        this.events = events;
        this.clock = clock;
        this.maxBytes = maxBytes;
    }

    @Override
    public Stored store(String punishmentIdentifier, String baseName, byte[] text, byte[] json, Map<String, Object> metadata) throws StoreException {
        if (text.length > this.maxBytes || json.length > this.maxBytes) {
            throw new StoreException("the generated file is larger than " + this.maxBytes + " bytes", true, null);
        }
        PunishmentEntity punishment = this.punishments.findById(punishmentIdentifier).orElse(null);
        if (punishment == null) {
            throw new StoreException("there is no such punishment", false, null);
        }
        String textKey = BlobKeys.generate(this.clock);
        String jsonKey = BlobKeys.generate(this.clock);
        try {
            this.blobs.put(textKey, new ByteArrayInputStream(text), text.length, "text/plain");
            this.blobs.put(jsonKey, new ByteArrayInputStream(json), json.length, "application/json");
            EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), punishmentIdentifier, punishment.getOwner(), null,
                    EvidenceKind.TRANSCRIPT, SOURCE_SYSTEM, this.clock.millis());
            item.setFile(textKey, baseName + TEXT_EXTENSION, "text/plain", text.length, sha256(text));
            item.setJsonFile(jsonKey, json.length, sha256(json));
            item.setContent(null, new HashMap<>(metadata));
            EvidenceItemEntity saved = this.items.save(item);
            record(saved, EvidenceAuditAction.ATTACHED, null, null);
            publish(EvidenceEvents.ATTACHED, EvidenceEvents.attached(saved));
            return new Stored(saved.getIdentifier(), text.length, json.length);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(textKey);
            deleteQuietly(jsonKey);
            throw new StoreException("the generated evidence could not be stored", false, e);
        }
    }

    @Override
    public Optional<Opened> open(UUID evidenceId, Variant variant, EvidenceAuditAction action, Attribution by) throws IOException {
        EvidenceItemEntity item = this.items.findById(evidenceId).filter(found -> found.getKind() == EvidenceKind.TRANSCRIPT).orElse(null);
        if (item == null) {
            return Optional.empty();
        }
        boolean text = variant == Variant.TEXT;
        String key = text ? item.getFileKey() : item.getJsonFileKey();
        Long size = text ? item.getFileSize() : item.getJsonFileSize();
        if (key == null || size == null || size <= 0) {
            return Optional.empty();
        }
        byte[] bytes;
        BlobStore.BlobContent blob = this.blobs.open(key, 0, size - 1);
        try (InputStream in = blob.stream()) {
            bytes = in.readAllBytes();
        }
        record(item, action, by, null);
        String name = item.getFileName();
        String base = name.endsWith(TEXT_EXTENSION) ? name.substring(0, name.length() - TEXT_EXTENSION.length()) : name;
        return Optional.of(new Opened(bytes, text ? "text/plain" : "application/json", base + (text ? TEXT_EXTENSION : ".json")));
    }

    @Override
    public boolean delete(UUID evidenceId, EvidenceDeletionReason reason) throws IOException {
        EvidenceItemEntity item = this.items.findById(evidenceId).filter(found -> found.getKind() == EvidenceKind.TRANSCRIPT).orElse(null);
        if (item == null) {
            return false;
        }
        if (item.getFileKey() != null) {
            this.blobs.delete(item.getFileKey());
        }
        if (item.getJsonFileKey() != null) {
            this.blobs.delete(item.getJsonFileKey());
        }
        this.items.delete(item);
        record(item, EvidenceAuditAction.DELETED, null, reason.name());
        publish(EvidenceEvents.DELETED, EvidenceEvents.deleted(item, reason));
        return true;
    }

    /** The audit line names the item, its punishment and owner (hashes and ids) and the actor - never content. */
    private void record(EvidenceItemEntity item, EvidenceAuditAction action, Attribution by, String reason) {
        EvidenceAuditEntity line = new EvidenceAuditEntity(UUID.randomUUID(), item.getIdentifier(), item.getPunishmentIdentifier(),
                item.getOwner(), action, by == null ? null : by.source(), by == null ? null : by.recordedByOid(),
                reason, null, this.clock.millis());
        line.setActorOid(by == null ? null : by.actorOid());
        this.audit.save(line);
    }

    private void publish(String eventType, Map<String, Object> payload) {
        try {
            this.events.publish(eventType, payload);
        } catch (RuntimeException e) {
            LOGGER.error("Could not publish {}: {}", eventType, e.getMessage());
        }
    }

    private void deleteQuietly(String key) {
        try {
            this.blobs.delete(key);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not remove the orphan transcript file {}; the housekeeping sweep will", key, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
