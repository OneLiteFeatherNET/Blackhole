package net.onelitefeather.blackhole.discord.testing;

import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Closed;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestBatch;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ingested;
import net.onelitefeather.blackhole.discord.backend.Tickets.LeakResult;
import net.onelitefeather.blackhole.discord.backend.Tickets.Opened;
import net.onelitefeather.blackhole.discord.backend.Tickets.Outbox;
import net.onelitefeather.blackhole.discord.backend.Tickets.OutboxItem;
import net.onelitefeather.blackhole.discord.backend.Tickets.Patch;
import net.onelitefeather.blackhole.discord.backend.Tickets.ProtocolEnsured;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.backend.Tickets.Uploaded;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An in-memory backend that enforces the rules the bot depends on (the channel is reported once, archive and delete need a
 * stored protocol, the completeness proof must equal the backend's tally) and can be told to fail. Each test builds its own.
 */
public final class FakeTicketBackend extends UnsupportedBackend {

    /** One ticket and everything the backend keeps for it. */
    public static final class Rec {
        public final String id = UUID.randomUUID().toString();
        public final String appealId = UUID.randomUUID().toString();
        public long no;
        public State state = State.PENDING_CHANNEL;
        public String channelId;
        public String player;
        public boolean leaked;
        public boolean frozen;
        public long cursor;
        public long relayedSeq;
        public boolean complete;
        public Integer count;
        public String last;
        public Integer protocolVersion;
        public String appealStatus = "SUBMITTED";
        /** Inbound messages by Discord id: the text now. */
        public final Map<Long, String> inbound = new TreeMap<>();
        public final Set<Long> deleted = new HashSet<>();
        public final Map<Long, Integer> edits = new HashMap<>();
        public final List<OutboxItem> outbox = new ArrayList<>();
        public final List<IngestItem> ingestedItems = new ArrayList<>();
        public final Map<String, String> relayed = new LinkedHashMap<>();
    }

    public final Map<String, Rec> tickets = new LinkedHashMap<>();
    public final List<String> calls = new ArrayList<>();
    public final List<String> staffLinks = new ArrayList<>();
    /** When set, every call fails with this status (0 = unreachable). */
    public Integer down;
    /** The next n calls fail with this status, then the backend is fine again. */
    public int failNext;
    public int failNextStatus = 503;
    public boolean protocolStoreFails;
    /** The next n close calls are answered with a transcript mismatch, whatever the proof says. */
    public int mismatchesToForce;
    public boolean undecided;
    public Result<Opened> openResult;
    public int ensureCalls;
    public int ingestCalls;
    public int protocolGeneration = 1;
    public boolean protocolStale;
    public final Set<String> ingestedLive = new HashSet<>();
    /** The current Ban IDs the backend knows (normalised) and the ticket each belongs to; a candidate not in here is a look-alike. */
    public final Map<String, Rec> banIds = new HashMap<>();
    /** Every leak check the backend received: what the bot sent. */
    public final List<LeakCall> leakChecks = new ArrayList<>();
    /** When set, a leak check is refused with this status (400 is a refusal, 503 an outage). */
    public Integer leakStatus;

    /** One leak check call. */
    public record LeakCall(List<String> candidates, boolean listed, String poster, boolean posterIsBot) {
    }

    public Rec add(long no, String player) {
        Rec rec = new Rec();
        rec.no = no;
        rec.player = player;
        this.tickets.put(rec.id, rec);
        return rec;
    }

    public void addOutbox(Rec rec, OutboxItem item) {
        rec.outbox.add(item);
    }

    /** Calls (by their label before a colon, such as {@code patch} or {@code ingest}) that fail until removed. */
    public final Set<String> failing = new HashSet<>();

    private <T> Result<T> gate(String label) {
        if (this.failing.contains(label) || this.failing.contains(label.split(":")[0])) {
            return Result.Failed.of(503, null);
        }
        if (this.down != null) {
            return Result.Failed.of(this.down, null);
        }
        if (this.failNext > 0) {
            this.failNext--;
            return Result.Failed.of(this.failNextStatus, null);
        }
        return null;
    }

    private static Ticket view(Rec r) {
        return new Ticket(r.id, r.no, r.state, r.appealId, r.channelId, r.player, r.leaked, r.frozen, Long.toString(r.cursor), r.relayedSeq,
                r.complete, r.count, r.last, r.protocolVersion);
    }

    public Ticket view(String id) {
        return view(this.tickets.get(id));
    }

    private int tallyCount(Rec r) {
        return (int) r.inbound.keySet().stream().filter(id -> !r.deleted.contains(id)).count();
    }

    private String tallyLast(Rec r) {
        return r.inbound.keySet().stream().filter(id -> !r.deleted.contains(id)).max(Long::compare).map(String::valueOf).orElse("0");
    }

    @Override
    public @NotNull Result<Opened> openTicket(@NotNull String discordUserId, @Nullable String displayName, @NotNull String banId, @Nullable String statement) {
        this.calls.add("open");
        Result<Opened> gate = gate("open");
        return gate != null ? gate : this.openResult;
    }

    @Override
    public @NotNull Result<List<Ticket>> tickets(@NotNull Collection<State> states) {
        this.calls.add("tickets");
        Result<List<Ticket>> gate = gate("tickets");
        if (gate != null) {
            return gate;
        }
        return new Result.Ok<>(this.tickets.values().stream().filter(r -> states.contains(r.state)).map(FakeTicketBackend::view).toList());
    }

    @Override
    public @NotNull Result<Ticket> ticket(@NotNull String id) {
        Result<Ticket> gate = gate("ticket");
        return gate != null ? gate : new Result.Ok<>(view(this.tickets.get(id)));
    }

    @Override
    public @NotNull Result<Ticket> patchTicket(@NotNull String id, @NotNull Patch patch) {
        this.calls.add("patch:" + (patch.state() != null ? patch.state() : patch.channelId() != null ? "channel" : patch.frozen() != null ? "frozen=" + patch.frozen() : "gone"));
        Result<Ticket> gate = gate(this.calls.getLast());
        if (gate != null) {
            return gate;
        }
        Rec r = this.tickets.get(id);
        if (Boolean.TRUE.equals(patch.channelGone())) {
            r.state = State.DELETED;
            return new Result.Ok<>(view(r));
        }
        if (patch.frozen() != null) {
            r.frozen = patch.frozen();
        }
        if (patch.channelId() != null && r.state == State.PENDING_CHANNEL) {
            r.channelId = patch.channelId();
            r.state = State.OPEN;
        }
        if (patch.state() != null && patch.state() != r.state) {
            switch (patch.state()) {
                case CLOSING -> {
                    if (r.state == State.OPEN) {
                        r.state = State.CLOSING;
                    }
                }
                case CLOSED -> {
                    if (r.state == State.OPEN || r.state == State.CLOSING) {
                        r.state = State.CLOSED;
                    }
                }
                case ARCHIVED -> {
                    if (r.state != State.CLOSED || !r.complete || !this.storedFor.contains(r.id)) {
                        return Result.Failed.of(409, "PROTOCOL_REQUIRED");
                    }
                    r.protocolVersion = this.protocolGeneration;
                    r.state = State.ARCHIVED;
                }
                case DELETED -> {
                    if (!r.complete || !this.storedFor.contains(r.id) || this.protocolStale) {
                        return Result.Failed.of(409, "PROTOCOL_REQUIRED");
                    }
                    r.state = State.DELETED;
                }
                default -> {
                    return Result.Failed.of(409, "INVALID_STATE");
                }
            }
        }
        return new Result.Ok<>(view(r));
    }

    private final Set<String> storedFor = new HashSet<>();

    /** A late edit made the stored protocol out of date: the next ensure makes a new version, and a delete before that is refused. */
    public void staleProtocol() {
        this.protocolStale = true;
    }

    @Override
    public @NotNull Result<Outbox> outbox(@NotNull String id, long afterSeq, int limit) {
        this.calls.add("outbox");
        Result<Outbox> gate = gate("outbox");
        if (gate != null) {
            return gate;
        }
        Rec r = this.tickets.get(id);
        List<OutboxItem> items = r.outbox.stream().filter(i -> i.seq() > afterSeq).limit(limit).toList();
        return new Result.Ok<>(new Outbox(items, r.relayedSeq));
    }

    @Override
    public @NotNull Result<Long> linkRelay(@NotNull String id, long seq, @Nullable String messageId, @Nullable String discordMessageId) {
        this.calls.add("link:" + seq);
        Result<Long> gate = gate("link");
        if (gate != null) {
            return gate;
        }
        Rec r = this.tickets.get(id);
        r.relayedSeq = Math.max(r.relayedSeq, seq);
        if (messageId != null && discordMessageId != null) {
            r.relayed.put(messageId, discordMessageId);
        }
        return new Result.Ok<>(r.relayedSeq);
    }

    /** A file the bot uploaded to the backend. */
    public record Upload(String ticketId, String name, byte[] data, String discordAttachmentId, String author) {
    }

    /** Files the outbox points at, by attachment id. */
    public final Map<String, byte[]> files = new HashMap<>();
    public final List<Upload> uploads = new ArrayList<>();
    /** Answers for an upload by Discord attachment id; others are accepted. */
    public final Map<String, Result<Uploaded>> uploadAnswers = new HashMap<>();
    public final List<String> fetches = new ArrayList<>();

    @Override
    public @NotNull Result<byte[]> fetchAttachment(@NotNull String id, @NotNull String attachmentId) {
        this.fetches.add(attachmentId);
        Result<byte[]> gate = gate("fetch");
        if (gate != null) {
            return gate;
        }
        byte[] data = this.files.get(attachmentId);
        return data == null ? Result.Failed.of(404, null) : new Result.Ok<>(data);
    }

    @Override
    public @NotNull Result<Uploaded> uploadAttachment(@NotNull String id, @NotNull byte[] data, @NotNull String name,
                                                      @NotNull String discordAttachmentId, @NotNull String authorDiscordUserId) {
        this.calls.add("upload");
        this.uploads.add(new Upload(id, name, data, discordAttachmentId, authorDiscordUserId));
        Result<Uploaded> gate = gate("upload");
        if (gate != null) {
            return gate;
        }
        Result<Uploaded> answer = this.uploadAnswers.get(discordAttachmentId);
        if (answer != null) {
            return answer;
        }
        return new Result.Ok<>(new Uploaded(UUID.nameUUIDFromBytes(discordAttachmentId.getBytes()).toString(), name, null, (long) data.length));
    }

    @Override
    public @NotNull Result<Ingested> ingest(@NotNull String id, @NotNull IngestBatch batch) {
        this.calls.add("ingest");
        this.ingestCalls++;
        Result<Ingested> gate = gate("ingest");
        if (gate != null) {
            return gate;
        }
        Rec r = this.tickets.get(id);
        int added = 0;
        int edited = 0;
        int removed = 0;
        for (IngestItem item : batch.items()) {
            r.ingestedItems.add(item);
            long messageId = Long.parseLong(item.discordMessageId());
            this.ingestedLive.add(item.discordMessageId());
            String known = r.inbound.get(messageId);
            if (known == null) {
                r.inbound.put(messageId, item.text());
                added++;
            } else if (!known.equals(item.text())) {
                r.inbound.put(messageId, item.text());
                r.edits.merge(messageId, 1, Integer::sum);
                edited++;
            }
            r.cursor = Math.max(r.cursor, messageId);
        }
        for (String delete : batch.deletes()) {
            if (r.deleted.add(Long.parseLong(delete))) {
                removed++;
            }
        }
        if (batch.complete()) {
            Set<Long> present = new HashSet<>();
            batch.presentMessageIds().forEach(m -> present.add(Long.parseLong(m)));
            for (Long known : new ArrayList<>(r.inbound.keySet())) {
                if (!present.contains(known) && r.deleted.add(known)) {
                    removed++;
                }
            }
        }
        return new Result.Ok<>(new Ingested(added, edited, removed, 0, Long.toString(r.cursor)));
    }

    @Override
    public @NotNull Result<Closed> close(@NotNull String id, int count, @Nullable String lastMessageId) {
        this.calls.add("close");
        Result<Closed> gate = gate("close");
        if (gate != null) {
            return gate;
        }
        Rec r = this.tickets.get(id);
        String last = lastMessageId == null ? "0" : lastMessageId;
        if (this.mismatchesToForce > 0) {
            this.mismatchesToForce--;
            return new Result.Failed<>(409, "TRANSCRIPT_MISMATCH", null, tallyCount(r) + 1, tallyLast(r));
        }
        if (count != tallyCount(r) || !last.equals(tallyLast(r))) {
            return new Result.Failed<>(409, "TRANSCRIPT_MISMATCH", null, tallyCount(r), tallyLast(r));
        }
        r.complete = true;
        r.count = count;
        r.last = last;
        if (r.state == State.OPEN || r.state == State.CLOSING) {
            r.state = State.CLOSED;
        }
        if (this.undecided) {
            return new Result.Ok<>(new Closed(view(r), null));
        }
        if (this.protocolStoreFails) {
            return Result.Failed.of(503, null);
        }
        this.storedFor.add(r.id);
        return new Result.Ok<>(new Closed(view(r), this.protocolGeneration));
    }

    @Override
    public @NotNull Result<ProtocolEnsured> ensureProtocol(@NotNull String id) {
        this.calls.add("ensure");
        this.ensureCalls++;
        Result<ProtocolEnsured> gate = gate("ensure");
        if (gate != null) {
            return gate;
        }
        if (this.protocolStoreFails) {
            return Result.Failed.of(503, null);
        }
        Rec r = this.tickets.get(id);
        if (this.undecided) {
            return Result.Failed.of(409, "INVALID_STATE");
        }
        if (this.protocolStale) {
            this.protocolGeneration++;
            this.protocolStale = false;
        }
        this.storedFor.add(r.id);
        if (r.protocolVersion != null) {
            r.protocolVersion = this.protocolGeneration;
        }
        return new Result.Ok<>(new ProtocolEnsured(this.protocolGeneration, true));
    }

    @Override
    public @NotNull Result<List<String>> staffLinks() {
        this.calls.add("staffLinks");
        Result<List<String>> gate = gate("staffLinks");
        return gate != null ? gate : new Result.Ok<>(List.copyOf(this.staffLinks));
    }

    @Override
    public @NotNull NotificationResult appeal(@NotNull String identifier) {
        this.calls.add("appeal");
        if (this.down != null) {
            return new NotificationResult.Unavailable(this.down);
        }
        return this.tickets.values().stream().filter(r -> r.appealId.equals(identifier)).findFirst()
                .<NotificationResult>map(r -> new NotificationResult.Found(new Notification(NotificationKind.APPEAL, identifier, null, null, "ref",
                        "section", null, r.appealStatus, 0, 0)))
                .orElseGet(NotificationResult.NotFound::new);
    }

    @Override
    public @NotNull LinkResult confirmLink(@NotNull String code, @NotNull String discordUserId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull WhoamiResult whoami(@NotNull String discordUserId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull UnlinkResult unlink(@NotNull String discordUserId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull NotificationResult report(@NotNull String identifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<LeakResult> leakCheck(@NotNull List<String> candidates, boolean listed, @Nullable String posterDiscordUserId, boolean posterIsBot) {
        Result<LeakResult> gate = gate("leak");
        if (gate != null) {
            this.calls.add("leak:failed");
            return gate;
        }
        if (this.leakStatus != null) {
            this.calls.add("leak:failed");
            return Result.Failed.of(this.leakStatus, null);
        }
        this.leakChecks.add(new LeakCall(List.copyOf(candidates), listed, posterDiscordUserId, posterIsBot));
        int matched = 0;
        int marked = 0;
        for (String candidate : candidates) {
            Rec rec = this.banIds.get(candidate);
            if (rec != null) {
                matched++;
                if (!rec.leaked) {
                    rec.leaked = true;
                    marked++;
                }
            }
        }
        return new Result.Ok<>(new LeakResult(matched, marked));
    }
}
