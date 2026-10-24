package net.onelitefeather.blackhole.backend.casetimeline.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseProtocolDTO;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolStore;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolVersion;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.service.GeneratedEvidenceGateway;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Staff access to the stored appeal protocols of a case: list the readable versions, open the text and
 * download the text or the JSON. Every open and download is audited through the evidence audit trail
 * ({@code VIEWED} / {@code DOWNLOADED}). A version a redaction made unreadable is not found.
 */
@Singleton
public class CaseProtocolService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseProtocolService.class);

    private final CaseReference references;
    private final ProtocolStore store;
    private final GeneratedEvidenceGateway evidence;

    public CaseProtocolService(PunishmentRepository punishments, ProtocolStore store, GeneratedEvidenceGateway evidence) {
        this.references = new CaseReference(punishments);
        this.store = store;
        this.evidence = evidence;
    }

    /** The readable versions of every appeal of the case, newest first. */
    public CaseProtocolDTO list(String reference) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found.kind(), found.message());
        }
        List<CaseProtocolDTO.Version> items = this.store.versionsOf(found.punishment().getIdentifier()).stream()
                .filter(version -> version.status().readable() && version.evidenceId() != null)
                .map(version -> new CaseProtocolDTO.Version(version.appealId(), version.version(), version.evidenceId(),
                        version.status() == ProtocolVersion.Status.ACTIVE, version.entryCount(), version.firstSeq(), version.lastSeq(),
                        version.sourceDigest(), version.createdAt()))
                .toList();
        return new CaseProtocolDTO.ListResponse(items);
    }

    /**
     * Opens the human-readable rendering and writes a {@code VIEWED} audit entry.
     *
     * @return the {@link CaseProtocolDTO.TextResponse}, or 404 (unknown, other case, unreadable), 503 (store down)
     */
    public CaseProtocolDTO view(String reference, UUID evidenceId, Actor actor) {
        return open(reference, evidenceId, GeneratedEvidenceGateway.Variant.TEXT, EvidenceAuditAction.VIEWED, actor, true);
    }

    /**
     * Downloads one rendering and writes a {@code DOWNLOADED} audit entry.
     *
     * @param format {@code text} (default) or {@code json}
     * @return the {@link CaseProtocolDTO.Download}, or 400 (unknown format), 404, 503
     */
    public CaseProtocolDTO download(String reference, UUID evidenceId, @Nullable String format, Actor actor) {
        String chosen = format == null ? "text" : format.toLowerCase(Locale.ROOT);
        if (!chosen.equals("text") && !chosen.equals("json")) {
            return error(CaseErrorKind.VALIDATION, "format is text or json");
        }
        GeneratedEvidenceGateway.Variant variant = chosen.equals("json") ? GeneratedEvidenceGateway.Variant.JSON : GeneratedEvidenceGateway.Variant.TEXT;
        return open(reference, evidenceId, variant, EvidenceAuditAction.DOWNLOADED, actor, false);
    }

    private CaseProtocolDTO open(String reference, UUID evidenceId, GeneratedEvidenceGateway.Variant variant, EvidenceAuditAction action,
                                 Actor actor, boolean asText) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found.kind(), found.message());
        }
        ProtocolVersion version = this.store.byEvidence(evidenceId).orElse(null);
        if (version == null || !version.status().readable() || !version.punishmentIdentifier().equals(found.punishment().getIdentifier())) {
            return error(CaseErrorKind.NOT_FOUND, "there is no such protocol");
        }
        try {
            GeneratedEvidenceGateway.Opened opened = this.evidence.open(evidenceId, variant, action, actor.attribution()).orElse(null);
            if (opened == null) {
                return error(CaseErrorKind.NOT_FOUND, "there is no such protocol");
            }
            if (asText) {
                return new CaseProtocolDTO.TextResponse(version.appealId(), version.version(), evidenceId,
                        new String(opened.bytes(), StandardCharsets.UTF_8));
            }
            return new CaseProtocolDTO.Download(opened.fileName(), opened.contentType(), opened.bytes());
        } catch (IOException e) {
            LOGGER.warn("Reading protocol evidence {} failed", evidenceId, e);
            return error(CaseErrorKind.UNAVAILABLE, "the protocol could not be read");
        }
    }

    private static CaseProtocolDTO.Error error(CaseErrorKind kind, String message) {
        return new CaseProtocolDTO.Error(message, kind);
    }
}
