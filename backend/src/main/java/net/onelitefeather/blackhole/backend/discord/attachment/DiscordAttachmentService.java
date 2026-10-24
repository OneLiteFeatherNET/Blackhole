package net.onelitefeather.blackhole.backend.discord.attachment;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.ChatFiles;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicket;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicketStore;
import net.onelitefeather.blackhole.backend.discord.ticket.Participant;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemRepository;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.service.ChatFileService;
import net.onelitefeather.blackhole.backend.publicapi.PublicAttachmentReader;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The files of a ticket channel, in both directions, behind the {@code DISCORD} role only.
 *
 * <p>Upload: the bot sends a file of a message it is about to ingest. Only the bound player and a verified staff member may send one
 * (anyone else answers {@link DiscordTicketDTO.Code#FILE_NOT_ACCEPTED}). The file goes through the same chat pipeline as the portal and
 * the panel ({@link ChatFileService}, the {@code CHAT} profile: scan, sniff, type, size, sanitising) and is stored bound to the case at
 * once, under an id derived from the ticket and the Discord attachment id, so a replay gets the same file. The Discord id itself is not
 * stored.</p>
 *
 * <p>Download: a file of a visible message or an active share of the ticket's case, by the same rule the player route uses
 * ({@link PublicAttachmentReader}). Anything else is 404.</p>
 */
@Singleton
public class DiscordAttachmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordAttachmentService.class);
    private static final Pattern SNOWFLAKE = Pattern.compile("^\\d{1,19}$");
    private static final Pattern USER_ID = Pattern.compile("^\\d{15,20}$");
    private static final String SCOPE_PREFIX = ChatFiles.PREFIX + "discord:";

    /** The answer of a download: the file to stream, or the error to answer. */
    public sealed interface Reply {
        /** A file to stream. */
        record Content(EvidenceDTO.Download download) implements Reply {
        }

        /** An expected failure, as the ticket facade answers it. */
        record Failure(DiscordTicketDTO.Error error) implements Reply {
        }
    }

    private final DiscordTicketStore tickets;
    private final DiscordLinkRepository links;
    private final ChatFileService files;
    private final PublicAttachmentReader reader;
    private final EvidenceItemRepository items;
    private final BlobStore blobs;

    public DiscordAttachmentService(DiscordTicketStore tickets, DiscordLinkRepository links, ChatFileService files,
                                    PublicAttachmentReader reader, EvidenceItemRepository items, BlobStore blobs) {
        this.tickets = tickets;
        this.links = links;
        this.files = files;
        this.reader = reader;
        this.items = items;
        this.blobs = blobs;
    }

    /** The scope of the files a ticket's channel sends: one per ticket, so the per-appeal file limit counts them too. */
    public static String scope(UUID ticketId) {
        return SCOPE_PREFIX + ticketId;
    }

    /**
     * Stores one file the channel sent, or returns the file it stored before.
     *
     * @return the {@link DiscordTicketDTO.Attachment}, or an error: 400, 403 {@code FILE_NOT_ACCEPTED}, 404, 409, 503
     */
    public DiscordTicketDTO upload(UUID ticketId, @Nullable String fileName, String discordAttachmentId, String authorDiscordUserId,
                                   InputStream content, Actor bot) {
        DiscordTicket ticket = this.tickets.ticket(ticketId).orElse(null);
        if (ticket == null) {
            return error(ErrorKind.NOT_FOUND, "there is no such ticket", null);
        }
        if (ticket.state() == TicketState.DELETED) {
            return error(ErrorKind.CONFLICT, "the channel of this ticket is deleted", DiscordTicketDTO.Code.INVALID_STATE);
        }
        if (!SNOWFLAKE.matcher(discordAttachmentId).matches() || !USER_ID.matcher(authorDiscordUserId).matches()) {
            return error(ErrorKind.VALIDATION, "a file needs its Discord attachment id and its author", null);
        }
        Attribution attribution = null;
        EvidenceProvenance provenance;
        Participant bound = this.tickets.participant(ticket.ownerParticipantId()).orElse(null);
        Optional<UUID> staff = staffOf(authorDiscordUserId);
        if (bound != null && authorDiscordUserId.equals(bound.discordUserId())) {
            provenance = EvidenceProvenance.PLAYER;
        } else if (staff.isPresent()) {
            provenance = EvidenceProvenance.STAFF;
            attribution = bot.attribution().withActorOid(staff.get());
        } else {
            return error(ErrorKind.FORBIDDEN, "only the bound player and verified staff may send files", DiscordTicketDTO.Code.FILE_NOT_ACCEPTED);
        }

        UUID identifier = identifier(ticketId, discordAttachmentId);
        Optional<EvidenceItemEntity> stored = this.items.findById(identifier);
        if (stored.isPresent() && scope(ticketId).equals(stored.get().getCorrelationId())) {
            return attachment(stored.get(), provenance);
        }
        EvidenceDTO result = this.files.stage(new ChatFileService.Stage(ticket.punishmentIdentifier(), scope(ticketId), provenance, attribution,
                fileName, identifier, true), content);
        return switch (result) {
            case EvidenceDTO.Staged staged -> new DiscordTicketDTO.Attachment(staged.attachmentId().toString(), staged.name(),
                    staged.contentType(), staged.size(), staged.width(), staged.height(), provenance.name());
            case EvidenceDTO.Error failed -> fromEvidence(failed);
            default -> error(ErrorKind.UNAVAILABLE, "the file could not be stored", null);
        };
    }

    /**
     * The accepted file behind an id a bot sent with an ingest: a file this ticket's channel stored, of this ticket's case, whose
     * provenance is the one the author has. Anything else is empty, so the message keeps the file as metadata.
     */
    public Optional<DiscordTicketDTO.Attachment> accepted(UUID ticketId, String punishmentIdentifier, UUID attachmentId,
                                                         EvidenceProvenance expected) {
        return this.items.findById(attachmentId)
                .filter(item -> scope(ticketId).equals(item.getCorrelationId()) && punishmentIdentifier.equals(item.getPunishmentIdentifier())
                        && item.getProvenance() == expected && item.getFileKey() != null)
                .map(item -> attachment(item, expected));
    }

    /**
     * Opens a file the ticket's case shows: a file of a visible message, or the image of an active share.
     *
     * @return the file to stream, or 404 for anything else, 503 when the store cannot answer
     */
    public Reply download(UUID ticketId, UUID attachmentId) {
        DiscordTicket ticket = this.tickets.ticket(ticketId).orElse(null);
        if (ticket == null) {
            return failure(ErrorKind.NOT_FOUND, "there is no such ticket");
        }
        PublicAttachmentReader.Visible visible = this.reader.find(ticket.punishmentIdentifier(), attachmentId).orElse(null);
        if (visible == null) {
            return failure(ErrorKind.NOT_FOUND, "there is no such file");
        }
        long last = Math.max(visible.size() - 1, 0);
        try {
            BlobStore.BlobContent blob = this.blobs.open(visible.fileKey(), 0, last);
            return new Reply.Content(new EvidenceDTO.Download(blob.stream(), blob.length(), 0, last, visible.size(), false,
                    visible.contentType(), visible.name()));
        } catch (BlobStore.NoSuchBlobException missing) {
            LOGGER.error("A visible ticket file points at a missing blob");
            return failure(ErrorKind.NOT_FOUND, "there is no such file");
        } catch (IOException e) {
            LOGGER.warn("Reading a ticket file failed", e);
            return failure(ErrorKind.UNAVAILABLE, "the file could not be read");
        }
    }

    /** The derived evidence id: a hash of the ticket and the Discord attachment id, never the Discord id itself. */
    static UUID identifier(UUID ticketId, String discordAttachmentId) {
        return UUID.nameUUIDFromBytes(("discord-file|" + ticketId + "|" + discordAttachmentId).getBytes(StandardCharsets.UTF_8));
    }

    private Optional<UUID> staffOf(String discordUserId) {
        return this.links.findByKindAndDiscordUserId(DiscordLinkKind.STAFF, discordUserId).map(DiscordLinkEntity::getSubject).flatMap(subject -> {
            try {
                return Optional.of(UUID.fromString(subject));
            } catch (IllegalArgumentException notAnOid) {
                return Optional.empty();
            }
        });
    }

    private static DiscordTicketDTO.Attachment attachment(EvidenceItemEntity item, EvidenceProvenance provenance) {
        Integer width = item.getPayload() != null && item.getPayload().get("width") instanceof Number number ? number.intValue() : null;
        Integer height = item.getPayload() != null && item.getPayload().get("height") instanceof Number number ? number.intValue() : null;
        return new DiscordTicketDTO.Attachment(item.getIdentifier().toString(), item.getFileName(), item.getFileContentType(), item.getFileSize(),
                width, height, provenance.name());
    }

    /** The public code for a storage refusal; the message of the evidence error is not passed on. */
    private static DiscordTicketDTO.Error fromEvidence(EvidenceDTO.Error failed) {
        return switch (failed.kind()) {
            case VALIDATION -> error(ErrorKind.VALIDATION, "the file is not valid", null);
            case NOT_FOUND -> error(ErrorKind.NOT_FOUND, "there is no such punishment", null);
            case CONFLICT -> error(ErrorKind.CONFLICT, "the file conflicts with the case", null);
            case PAYLOAD_TOO_LARGE -> error(ErrorKind.VALIDATION, "the file is too large", DiscordTicketDTO.Code.TOO_LARGE);
            case UNSUPPORTED_MEDIA_TYPE -> error(ErrorKind.VALIDATION, "the file type is not allowed", DiscordTicketDTO.Code.UNSUPPORTED_TYPE);
            case UNPROCESSABLE -> error(ErrorKind.VALIDATION, "the file is not valid", DiscordTicketDTO.Code.INVALID_FILE);
            case FILE_FLAGGED -> error(ErrorKind.VALIDATION, "the file was flagged", DiscordTicketDTO.Code.FILE_FLAGGED);
            case FILE_ACTIVE_CONTENT -> error(ErrorKind.VALIDATION, "the file carries active content", DiscordTicketDTO.Code.FILE_ACTIVE_CONTENT);
            case QUOTA -> error(ErrorKind.CONFLICT, "this ticket has reached the file limit", DiscordTicketDTO.Code.QUOTA);
            case UNAVAILABLE -> error(ErrorKind.UNAVAILABLE, "the file could not be stored", null);
        };
    }

    private static DiscordTicketDTO.Error error(ErrorKind kind, String message, @Nullable DiscordTicketDTO.Code code) {
        return DiscordTicketDTO.Error.of(message, kind, code);
    }

    private static Reply.Failure failure(ErrorKind kind, String message) {
        return new Reply.Failure(error(kind, message, null));
    }
}
