package net.onelitefeather.blackhole.backend.publicapi.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.ChatFiles;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.service.ChatFileService;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobStore;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.PublicAttachmentReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicAttachmentDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * The player's chat files: upload (staged), remove a staged file, and download. Every method works on the session's own punishment, and
 * a leaked Ban ID (scope {@code LIMITED}) gets 403 on all of them. An unknown, malformed or foreign id is the same 404.
 */
@Singleton
public class PublicAttachmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAttachmentService.class);

    private final PublicAppealReader appeals;
    private final ChatFileService files;
    private final PublicAttachmentReader reader;
    private final BlobStore blobs;

    public PublicAttachmentService(PublicAppealReader appeals, ChatFileService files, PublicAttachmentReader reader, BlobStore blobs) {
        this.appeals = appeals;
        this.files = files;
        this.reader = reader;
        this.blobs = blobs;
    }

    /**
     * Scans and stages one file for the open appeal.
     *
     * @return the {@link PublicAttachmentDTO.Staged} file, or an error: 403, 409 {@code NO_APPEAL} or {@code QUOTA}, 413, 415, 422, 503
     */
    public PublicAttachmentDTO stage(PlayerSession session, @Nullable String fileName, InputStream content) {
        if (session.scope() == PlayerScope.LIMITED) {
            return error("FORBIDDEN", PublicErrorKind.FORBIDDEN);
        }
        Optional<UUID> appeal = openAppeal(session);
        if (appeal.isEmpty()) {
            return error("NO_APPEAL", PublicErrorKind.CONFLICT);
        }
        EvidenceDTO staged = this.files.stage(new ChatFileService.Stage(session.punishmentIdentifier(), ChatFiles.appealScope(appeal.get()),
                EvidenceProvenance.PLAYER, null, fileName), content);
        return switch (staged) {
            case EvidenceDTO.Staged file -> new PublicAttachmentDTO.Staged(file.attachmentId(), file.name(), file.contentType(), file.size(),
                    file.width(), file.height());
            case EvidenceDTO.Error failed -> fromEvidence(failed);
            default -> error("UNAVAILABLE", PublicErrorKind.UNAVAILABLE);
        };
    }

    /**
     * Removes a staged file of the open appeal at once (the composer's remove button). A file that a message already sent is not found.
     *
     * @return {@link PublicAttachmentDTO.Deleted}, or 403 / 404
     */
    public PublicAttachmentDTO unstage(PlayerSession session, String id) {
        if (session.scope() == PlayerScope.LIMITED) {
            return error("FORBIDDEN", PublicErrorKind.FORBIDDEN);
        }
        Optional<UUID> attachment = parse(id);
        Optional<UUID> appeal = openAppeal(session);
        if (attachment.isEmpty() || appeal.isEmpty()) {
            return error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
        }
        return this.files.unstage(ChatFiles.appealScope(appeal.get()), attachment.get())
                ? new PublicAttachmentDTO.Deleted()
                : error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
    }

    /**
     * Opens a file the session may download: a file of a visible message, or the image of an active share.
     *
     * @return the {@link PublicAttachmentDTO.Content} to stream, or 403 for a leaked Ban ID, 404 for anything the session may not see,
     *         503 when the store cannot answer
     */
    public PublicAttachmentDTO download(PlayerSession session, String id) {
        if (session.scope() == PlayerScope.LIMITED) {
            return error("FORBIDDEN", PublicErrorKind.FORBIDDEN);
        }
        Optional<UUID> identifier = parse(id);
        if (identifier.isEmpty()) {
            return error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
        }
        Optional<PublicAttachmentReader.Visible> visible = this.reader.find(session.punishmentIdentifier(), identifier.get());
        if (visible.isEmpty()) {
            return error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
        }
        PublicAttachmentReader.Visible file = visible.get();
        long last = Math.max(file.size() - 1, 0);
        try {
            BlobStore.BlobContent blob = this.blobs.open(file.fileKey(), 0, last);
            return new PublicAttachmentDTO.Content(new EvidenceDTO.Download(blob.stream(), blob.length(), 0, last, file.size(), false,
                    file.contentType(), file.name()));
        } catch (BlobStore.NoSuchBlobException missing) {
            LOGGER.error("A visible chat file points at a missing blob");
            return error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
        } catch (IOException e) {
            LOGGER.warn("Reading a chat file failed", e);
            return error("UNAVAILABLE", PublicErrorKind.UNAVAILABLE);
        }
    }

    private Optional<UUID> openAppeal(PlayerSession session) {
        return this.appeals.list(session.punishmentIdentifier()).stream().filter(PublicAppealReader.Row::open)
                .map(PublicAppealReader.Row::identifier).findFirst();
    }

    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** The public code for a storage error. The message of the evidence error is never passed on. */
    private static PublicAttachmentDTO.Error fromEvidence(EvidenceDTO.Error failed) {
        return switch (failed.kind()) {
            case VALIDATION -> error("VALIDATION", PublicErrorKind.VALIDATION);
            case NOT_FOUND -> error("NOT_FOUND", PublicErrorKind.NOT_FOUND);
            case CONFLICT -> error("CONFLICT", PublicErrorKind.CONFLICT);
            case PAYLOAD_TOO_LARGE -> error("TOO_LARGE", PublicErrorKind.TOO_LARGE);
            case UNSUPPORTED_MEDIA_TYPE -> error("UNSUPPORTED_TYPE", PublicErrorKind.UNSUPPORTED_TYPE);
            case UNPROCESSABLE -> error("INVALID_FILE", PublicErrorKind.REJECTED_FILE);
            case FILE_FLAGGED -> error("FILE_FLAGGED", PublicErrorKind.REJECTED_FILE);
            case FILE_ACTIVE_CONTENT -> error("FILE_ACTIVE_CONTENT", PublicErrorKind.REJECTED_FILE);
            case QUOTA -> error("QUOTA", PublicErrorKind.CONFLICT);
            case UNAVAILABLE -> error("UNAVAILABLE", PublicErrorKind.UNAVAILABLE);
        };
    }

    private static PublicAttachmentDTO.Error error(String code, PublicErrorKind kind) {
        return new PublicAttachmentDTO.Error(code, kind);
    }
}
