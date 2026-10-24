package net.onelitefeather.blackhole.discord.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.client.api.DiscordLinkApi;
import net.onelitefeather.blackhole.client.api.DiscordNotificationsApi;
import net.onelitefeather.blackhole.client.api.DiscordTicketsApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordAppealNotification;
import net.onelitefeather.blackhole.client.model.DiscordIngestAttachment;
import net.onelitefeather.blackhole.client.model.DiscordIngestEmbed;
import net.onelitefeather.blackhole.client.model.DiscordIngestItem;
import net.onelitefeather.blackhole.client.model.DiscordIngestRequest;
import net.onelitefeather.blackhole.client.model.DiscordLeakChannelKind;
import net.onelitefeather.blackhole.client.model.DiscordLeakCheckRequest;
import net.onelitefeather.blackhole.client.model.DiscordLinkConfirmRequest;
import net.onelitefeather.blackhole.client.model.DiscordLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.DiscordLinkKind;
import net.onelitefeather.blackhole.client.model.DiscordLinkRemovedResponse;
import net.onelitefeather.blackhole.client.model.DiscordLinkWhoamiResponse;
import net.onelitefeather.blackhole.client.model.DiscordOutboxItem;
import net.onelitefeather.blackhole.client.model.DiscordReportNotification;
import net.onelitefeather.blackhole.client.model.DiscordStaffLink;
import net.onelitefeather.blackhole.client.model.DiscordTicketCloseRequest;
import net.onelitefeather.blackhole.client.model.DiscordTicketLinkRequest;
import net.onelitefeather.blackhole.client.model.DiscordTicketOpenRequest;
import net.onelitefeather.blackhole.client.model.DiscordTicketPatchRequest;
import net.onelitefeather.blackhole.client.model.MessageAuthorKind;
import net.onelitefeather.blackhole.client.model.TicketState;
import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.Closed;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestBatch;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ingested;
import net.onelitefeather.blackhole.discord.backend.Tickets.LeakResult;
import net.onelitefeather.blackhole.discord.backend.Tickets.Opened;
import net.onelitefeather.blackhole.discord.backend.Tickets.Outbox;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxFile;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxKind;
import net.onelitefeather.blackhole.discord.backend.Tickets.Uploaded;
import net.onelitefeather.blackhole.discord.backend.Tickets.Patch;
import net.onelitefeather.blackhole.discord.backend.Tickets.ProtocolEnsured;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@link BackendGateway} over the generated client. The code, the Ban ID, message text and the Discord user id are
 * never logged.
 */
public final class HttpBackendGateway implements BackendGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpBackendGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DiscordLinkApi api;
    private final DiscordNotificationsApi notifications;
    private final DiscordTicketsApi ticketApi;

    public HttpBackendGateway(@NotNull ApiClient client) {
        this.api = new DiscordLinkApi(client);
        this.notifications = new DiscordNotificationsApi(client);
        this.ticketApi = new DiscordTicketsApi(client);
    }

    @Override
    public @NotNull LinkResult confirmLink(@NotNull String code, @NotNull String discordUserId) {
        try {
            DiscordLinkConfirmResponse response = this.api.confirmDiscordLink(
                    new DiscordLinkConfirmRequest().code(code).discordUserId(discordUserId));
            return new LinkResult.Linked(kind(response.getKind()), response.getDisplayName());
        } catch (ApiException e) {
            // 400 is the backend's single INVALID_CODE answer; the Discord user id is always well formed here.
            if (e.getCode() == 400) {
                return new LinkResult.InvalidCode();
            }
            LOGGER.error("Confirming a Discord link failed with status {}", e.getCode());
            return new LinkResult.Unavailable(e.getCode());
        }
    }

    @Override
    public @NotNull WhoamiResult whoami(@NotNull String discordUserId) {
        try {
            DiscordLinkWhoamiResponse response = this.api.lookupDiscordLink(discordUserId);
            List<LinkEntry> links = response.getLinks().stream()
                    .map(entry -> new LinkEntry(kind(entry.getKind()), entry.getDisplayName()))
                    .toList();
            return new WhoamiResult.Found(links);
        } catch (ApiException e) {
            LOGGER.error("Looking up a Discord link failed with status {}", e.getCode());
            return new WhoamiResult.Unavailable(e.getCode());
        }
    }

    @Override
    public @NotNull UnlinkResult unlink(@NotNull String discordUserId) {
        try {
            DiscordLinkRemovedResponse response = this.api.unlinkDiscordUser(discordUserId);
            return new UnlinkResult.Removed(response.getRemoved() == null ? 0 : response.getRemoved());
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return new UnlinkResult.NotLinked();
            }
            LOGGER.error("Unlinking a Discord user failed with status {}", e.getCode());
            return new UnlinkResult.Unavailable(e.getCode());
        }
    }

    @Override
    public @NotNull NotificationResult report(@NotNull String identifier) {
        try {
            DiscordReportNotification r = this.notifications.getReportNotification(java.util.UUID.fromString(identifier));
            return new NotificationResult.Found(new Notification(NotificationKind.REPORT, r.getIdentifier().toString(), null,
                    r.getPlayerName(), r.getPlayerRef(), r.getCategory(), r.getDescription(), r.getStatus(),
                    r.getCreatedAt(), r.getUpdatedAt()));
        } catch (IllegalArgumentException e) {
            return new NotificationResult.NotFound();
        } catch (ApiException e) {
            return unavailableOrMissing("report", e);
        }
    }

    @Override
    public @NotNull NotificationResult appeal(@NotNull String identifier) {
        try {
            DiscordAppealNotification a = this.notifications.getAppealNotification(java.util.UUID.fromString(identifier));
            return new NotificationResult.Found(new Notification(NotificationKind.APPEAL, a.getIdentifier().toString(),
                    a.getPunishmentIdentifier(), a.getPlayerName(), a.getPlayerRef(), a.getSection(), a.getStatement(),
                    a.getStatus(), a.getCreatedAt(), a.getUpdatedAt()));
        } catch (IllegalArgumentException e) {
            return new NotificationResult.NotFound();
        } catch (ApiException e) {
            return unavailableOrMissing("appeal", e);
        }
    }

    private static NotificationResult unavailableOrMissing(String what, ApiException e) {
        if (e.getCode() == 404) {
            return new NotificationResult.NotFound();
        }
        LOGGER.error("Reading the {} notification failed with status {}", what, e.getCode());
        return new NotificationResult.Unavailable(e.getCode());
    }

    private static LinkKind kind(DiscordLinkKind kind) {
        return kind == DiscordLinkKind.PLAYER ? LinkKind.PLAYER : LinkKind.STAFF;
    }

    // ---- tickets ----

    @FunctionalInterface
    private interface ApiCall<T> {
        T run() throws ApiException;
    }

    /** Runs a call; a refusal or outage becomes a {@link Result.Failed} carrying the backend's error code. No body is logged. */
    private static <T> Result<T> invoke(String what, ApiCall<T> call) {
        try {
            return new Result.Ok<>(call.run());
        } catch (ApiException e) {
            return failed(what, e);
        }
    }

    private static <T> Result<T> failed(String what, ApiException e) {
        String code = null;
        Long availableFrom = null;
        Integer backendCount = null;
        String backendLast = null;
        String body = e.getResponseBody();
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = JSON.readTree(body);
                code = root.hasNonNull("code") ? root.get("code").asText() : null;
                availableFrom = root.hasNonNull("availableFrom") ? root.get("availableFrom").asLong() : null;
                backendCount = root.hasNonNull("backendCount") ? root.get("backendCount").asInt() : null;
                backendLast = root.hasNonNull("backendLastMessageId") ? root.get("backendLastMessageId").asText() : null;
            } catch (IOException ignored) {
                // Not our error shape (an edge page, for example): only the status counts.
            }
        }
        if (e.getCode() == 0 || e.getCode() >= 500 || e.getCode() == 429 || e.getCode() == 401) {
            LOGGER.warn("The backend could not serve a call ({}), status {}", what, e.getCode());
        }
        return new Result.Failed<>(e.getCode(), code, availableFrom, backendCount, backendLast);
    }

    private static Ticket ticket(net.onelitefeather.blackhole.client.model.DiscordTicket t) {
        return new Ticket(t.getId().toString(), t.getTicketNo(), State.valueOf(t.getState().getValue()), t.getAppealId().toString(),
                t.getChannelId(), t.getPlayerDiscordUserId(), Boolean.TRUE.equals(t.getLeaked()), Boolean.TRUE.equals(t.getFrozen()),
                t.getIngestedCursor() == null ? "0" : t.getIngestedCursor(), t.getRelayedSeq() == null ? 0 : t.getRelayedSeq(),
                Boolean.TRUE.equals(t.getTranscriptComplete()), t.getTranscriptCount(), t.getTranscriptLastMessageId(), t.getProtocolVersion());
    }

    @Override
    public @NotNull Result<Opened> openTicket(@NotNull String discordUserId, @Nullable String displayName, @NotNull String banId, @Nullable String statement) {
        return invoke("opening a ticket", () -> {
            var opened = this.ticketApi.openDiscordTicket(new DiscordTicketOpenRequest()
                    .discordUserId(discordUserId).displayName(displayName).banId(banId).statement(statement));
            return new Opened(ticket(opened.getTicket()), Boolean.TRUE.equals(opened.getCreated()), Boolean.TRUE.equals(opened.getAppealFiled()));
        });
    }

    @Override
    public @NotNull Result<List<Ticket>> tickets(@NotNull Collection<State> states) {
        return invoke("listing tickets", () -> this.ticketApi.listDiscordTickets(
                        states.stream().map(state -> TicketState.fromValue(state.name())).toList()).getTickets().stream()
                .map(HttpBackendGateway::ticket).toList());
    }

    @Override
    public @NotNull Result<Ticket> ticket(@NotNull String id) {
        return invoke("reading a ticket", () -> ticket(this.ticketApi.getDiscordTicket(UUID.fromString(id))));
    }

    @Override
    public @NotNull Result<Ticket> patchTicket(@NotNull String id, @NotNull Patch patch) {
        return invoke("updating a ticket", () -> {
            DiscordTicketPatchRequest request = new DiscordTicketPatchRequest().channelId(patch.channelId()).guildId(patch.guildId())
                    .frozen(patch.frozen()).channelGone(patch.channelGone());
            if (patch.state() != null) {
                request.state(TicketState.fromValue(patch.state().name()));
            }
            return ticket(this.ticketApi.patchDiscordTicket(UUID.fromString(id), request));
        });
    }

    @Override
    public @NotNull Result<Outbox> outbox(@NotNull String id, long afterSeq, int limit) {
        return invoke("reading the outbox", () -> {
            var outbox = this.ticketApi.getDiscordTicketOutbox(UUID.fromString(id), afterSeq, limit);
            List<OutboxItem> items = outbox.getItems().stream().map(HttpBackendGateway::outboxItem).toList();
            return new Outbox(items, outbox.getRelayedSeq() == null ? 0 : outbox.getRelayedSeq());
        });
    }

    private static OutboxItem outboxItem(DiscordOutboxItem item) {
        MessageAuthorKind author = item.getAuthorKind();
        return new OutboxItem(item.getSeq(), OutboxKind.valueOf(item.getKind().getValue()), item.getMessageId(),
                author == null ? null : AuthorKind.valueOf(author.getValue()), item.getText(), item.getDiscordMessageId(),
                item.getFiles() == null ? List.of() : item.getFiles().stream()
                        .map(f -> new OutboxFile(f.getAttachmentId(), f.getName(), f.getContentType(), f.getSize())).toList());
    }

    @Override
    public @NotNull Result<Long> linkRelay(@NotNull String id, long seq, @Nullable String messageId, @Nullable String discordMessageId) {
        return invoke("acknowledging a relay", () -> this.ticketApi.linkDiscordTicketMessage(UUID.fromString(id), new DiscordTicketLinkRequest()
                .seq(seq).messageId(messageId == null ? null : UUID.fromString(messageId)).discordMessageId(discordMessageId)).getRelayedSeq());
    }

    @Override
    public @NotNull Result<byte[]> fetchAttachment(@NotNull String id, @NotNull String attachmentId) {
        return invoke("fetching an attachment", () -> {
            java.io.File file = this.ticketApi.getDiscordTicketAttachment(UUID.fromString(id), UUID.fromString(attachmentId));
            try {
                return file == null ? new byte[0] : Files.readAllBytes(file.toPath());
            } catch (IOException e) {
                throw new ApiException(e);
            } finally {
                if (file != null) {
                    file.delete();
                }
            }
        });
    }

    @Override
    public @NotNull Result<Uploaded> uploadAttachment(@NotNull String id, @NotNull byte[] data, @NotNull String name,
                                                      @NotNull String discordAttachmentId, @NotNull String authorDiscordUserId) {
        return invoke("uploading an attachment", () -> {
            Path dir = null;
            Path file = null;
            try {
                dir = Files.createTempDirectory("blackhole-upload");
                file = dir.resolve(safeName(name));
                Files.write(file, data);
                var stored = this.ticketApi.uploadDiscordTicketAttachment(UUID.fromString(id), file.toFile(), discordAttachmentId, authorDiscordUserId);
                return new Uploaded(stored.getAttachmentId().toString(), stored.getName(), stored.getContentType(), stored.getSize());
            } catch (IOException e) {
                throw new ApiException(e);
            } finally {
                try {
                    if (file != null) {
                        Files.deleteIfExists(file);
                    }
                    if (dir != null) {
                        Files.deleteIfExists(dir);
                    }
                } catch (IOException ignored) {
                    // A temp file left behind is the operating system's to clean.
                }
            }
        });
    }

    /** The last path segment of a name from Discord, with nothing that could leave the temp directory. */
    static String safeName(String name) {
        String last = name == null ? "" : name.replace('\\', '/');
        last = last.substring(last.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        return last.isEmpty() || last.equals(".") || last.equals("..") ? "file" : last;
    }

    @Override
    public @NotNull Result<Ingested> ingest(@NotNull String id, @NotNull IngestBatch batch) {
        return invoke("recording messages", () -> {
            DiscordIngestRequest request = new DiscordIngestRequest().items(batch.items().stream().map(HttpBackendGateway::ingestItem).toList())
                    .deletes(batch.deletes()).complete(batch.complete()).presentMessageIds(batch.presentMessageIds());
            var result = this.ticketApi.ingestDiscordTicketMessages(UUID.fromString(id), request);
            return new Ingested(result.getAdded(), result.getEdited(), result.getDeleted(), result.getUnchanged(), result.getIngestedCursor());
        });
    }

    private static DiscordIngestItem ingestItem(IngestItem item) {
        return new DiscordIngestItem().discordMessageId(item.discordMessageId()).authorDiscordUserId(item.authorDiscordUserId())
                .authorName(item.authorName()).bot(item.bot()).hasStaffRole(item.hasStaffRole()).createdAt(item.createdAt())
                .editedAt(item.editedAt()).text(item.text())
                .attachments(item.attachments().stream().map(a -> new DiscordIngestAttachment().kind(a.kind()).id(a.id()).name(a.name())
                        .contentType(a.contentType()).size(a.size()).width(a.width()).height(a.height())
                        .attachmentId(a.attachmentId()).rejected(a.rejected())).toList())
                .embeds(item.embeds().stream().map(e -> new DiscordIngestEmbed().type(e.type()).host(e.host())).toList());
    }

    @Override
    public @NotNull Result<Closed> close(@NotNull String id, int count, @Nullable String lastMessageId) {
        return invoke("closing a ticket", () -> {
            var closed = this.ticketApi.closeDiscordTicket(UUID.fromString(id), new DiscordTicketCloseRequest().count(count).lastMessageId(lastMessageId));
            return new Closed(ticket(closed.getTicket()), closed.getProtocolVersion());
        });
    }

    @Override
    public @NotNull Result<ProtocolEnsured> ensureProtocol(@NotNull String id) {
        return invoke("ensuring the protocol", () -> {
            var ensured = this.ticketApi.ensureDiscordTicketProtocol(UUID.fromString(id));
            return new ProtocolEnsured(ensured.getProtocolVersion(), Boolean.TRUE.equals(ensured.getStored()));
        });
    }

    @Override
    public @NotNull Result<List<String>> staffLinks() {
        return invoke("listing staff links", () -> this.ticketApi.listDiscordStaffLinks().getLinks().stream()
                .map(DiscordStaffLink::getDiscordUserId).toList());
    }

    @Override
    public @NotNull Result<LeakResult> leakCheck(@NotNull List<String> candidates, boolean listed, @Nullable String posterDiscordUserId, boolean posterIsBot) {
        return invoke("checking leak candidates", () -> {
            var result = this.ticketApi.checkDiscordLeak(new DiscordLeakCheckRequest().candidates(candidates)
                    .channelKind(listed ? DiscordLeakChannelKind.LISTED : DiscordLeakChannelKind.PUBLIC)
                    .posterDiscordUserId(posterDiscordUserId).posterIsBot(posterIsBot));
            return new LeakResult(result.getMatched(), result.getMarked());
        });
    }
}
