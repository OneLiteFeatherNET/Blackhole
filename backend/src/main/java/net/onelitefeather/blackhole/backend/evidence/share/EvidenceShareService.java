package net.onelitefeather.blackhole.backend.evidence.share;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPublisher;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseReference;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemRepository;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Shows one existing evidence item to the appealing player, explicitly, revocably and minimised. Nothing is visible to a player without
 * an active share. Expected failures come back as {@link EvidenceShareDTO.Error}; share and revoke are one statement each (row and case
 * event together, {@link EvidenceShareStore}), and the {@code case.*} domain events go out after the write and carry no content.
 *
 * <p>An excerpt stores line indexes only. What the player sees is rendered from the evidence when they ask ({@link ExcerptRenderer}),
 * so there is no second copy of other players' words and a deleted item ends the share without another job.</p>
 */
@Singleton
public class EvidenceShareService {

    /** The most lines of a chat capture one share may show, {@code blackhole.chat.share.max-lines}. */
    public static final int DEFAULT_MAX_LINES = 20;

    private final CaseReference references;
    private final EvidenceItemRepository items;
    private final EvidenceShareStore shares;
    private final CaseChanges changes;
    private final CaseReader reader;
    private final CaseEventPublisher publisher;
    private final Clock clock;
    private final int maxLines;

    public EvidenceShareService(
            PunishmentRepository punishments, EvidenceItemRepository items, EvidenceShareStore shares, CaseChanges changes, CaseReader reader,
            DomainEventPublisher publisher, Clock clock, @Value("${blackhole.chat.share.max-lines:" + DEFAULT_MAX_LINES + "}") int maxLines
    ) {
        this.references = new CaseReference(punishments);
        this.items = items;
        this.shares = shares;
        this.changes = changes;
        this.reader = reader;
        this.publisher = new CaseEventPublisher(publisher);
        this.clock = clock;
        this.maxLines = Math.max(1, maxLines);
    }

    /** What the player would see for the selection; reads and changes nothing. */
    public EvidenceShareDTO preview(String reference, EvidenceShareDTO.PreviewRequest request) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found.kind(), found.message(), "NOT_FOUND");
        }
        Checked checked = check(found.punishment(), request.evidenceId(), request.selection(), request.appellantSpeaker());
        return checked.error() != null ? checked.error() : checked.preview();
    }

    /**
     * Shares an item with the player of the case's appeal.
     *
     * @return the {@link EvidenceShareDTO.Share} (with {@code repeated} when an identical active share existed), or an error: 400
     *         (no confirmation, bad selection), 404, 409 {@code NO_APPEAL} / {@code FROZEN} / {@code NOT_VALID}, 422 {@code NOT_SHAREABLE} /
     *         {@code PII_REJECTED}
     */
    public EvidenceShareDTO share(String reference, EvidenceShareDTO.CreateRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found.kind(), found.message(), "NOT_FOUND");
        }
        if (!request.confirmedMinimised()) {
            return error(CaseErrorKind.VALIDATION, "confirmedMinimised must be true: check the preview first", "NOT_CONFIRMED");
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        Checked checked = check(punishment, request.evidenceId(), request.selection(), request.appellantSpeaker());
        if (checked.error() != null) {
            return checked.error();
        }
        if (this.reader.find(id).filter(CaseReader.CaseRow::leaked).isPresent()) {
            return error(CaseErrorKind.CONFLICT, "the Ban ID of this case is marked leaked; nothing can be shared", "FROZEN");
        }
        Optional<UUID> appeal = this.shares.latestAppeal(id);
        if (appeal.isEmpty()) {
            return error(CaseErrorKind.CONFLICT, "the case has no appeal to share evidence in", "NO_APPEAL");
        }
        long now = this.clock.millis();
        UUID shareId = UUID.randomUUID();
        boolean excerpt = checked.excerpt();
        EvidenceShareStore.Share row = new EvidenceShareStore.Share(shareId, id, appeal.get(), request.evidenceId(), excerpt ? "EXCERPT" : "FILE",
                checked.selection(), excerpt ? request.appellantSpeaker() : null, true, actor.oid(), now, null, null, null);
        CaseActor by = CaseActor.of(actor.attribution());
        CaseEventPayload.EvidenceShared payload = new CaseEventPayload.EvidenceShared(shareId.toString(), checked.kind().name(), row.mode(),
                excerpt ? checked.selection().size() : null);
        this.changes.ensureCase(id, now);
        EvidenceShareStore.Created created = this.shares.create(row,
                new CaseEventDraft(id, payload, now, CaseEventOrigin.LIVE, by, shareId.toString(), "evidence.shared:" + shareId, CaseEventDraft.PLAYER));
        if (!created.repeated()) {
            Map<String, Object> change = new HashMap<>();
            change.put("shareId", shareId.toString());
            change.put("evidenceKind", checked.kind().name());
            change.put("mode", row.mode());
            if (excerpt) {
                change.put("lineCount", checked.selection().size());
            }
            this.publisher.publish(punishment, created.event(), by, now, change);
        }
        return toDto(created.share(), true, created.repeated());
    }

    /**
     * Withdraws a share. Revoking again changes nothing and answers the share as revoked.
     *
     * @return the {@link EvidenceShareDTO.Share}, or 404
     */
    public EvidenceShareDTO revoke(String reference, UUID shareId, EvidenceShareDTO.RevokeRequest request, Actor actor) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found.kind(), found.message(), "NOT_FOUND");
        }
        if (request == null || request.reason() == null) {
            return error(CaseErrorKind.VALIDATION, "a reason is required: MISTAKE, PRIVACY or OTHER", "NO_REASON");
        }
        PunishmentEntity punishment = found.punishment();
        String id = punishment.getIdentifier();
        Optional<EvidenceShareStore.Share> existing = this.shares.find(shareId, id);
        if (existing.isEmpty()) {
            return error(CaseErrorKind.NOT_FOUND, "there is no such share", "NOT_FOUND");
        }
        long now = this.clock.millis();
        CaseActor by = CaseActor.of(actor.attribution());
        Optional<CaseChanges.Appended> appended = Optional.empty();
        if (existing.get().active()) {
            appended = this.shares.revoke(shareId, id, now, actor.oid(), request.reason().name(),
                    new CaseEventDraft(id, new CaseEventPayload.EvidenceShareRevoked(shareId.toString(), request.reason().name()), now,
                            CaseEventOrigin.LIVE, by, shareId.toString(), "evidence.share_revoked:" + shareId, CaseEventDraft.PLAYER));
        }
        appended.ifPresent(event -> this.publisher.publish(punishment, event, by, now,
                Map.of("shareId", shareId.toString(), "reason", request.reason().name())));
        EvidenceShareStore.Share current = this.shares.find(shareId, id).orElse(existing.get());
        return toDto(current, true, false);
    }

    /** The shares of a case, oldest first, with their state. */
    public EvidenceShareDTO list(String reference) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return error(found.kind(), found.message(), "NOT_FOUND");
        }
        List<EvidenceShareDTO.Share> items = new ArrayList<>();
        for (EvidenceShareStore.Share share : this.shares.list(found.punishment().getIdentifier())) {
            items.add(toDto(share, this.items.findById(share.evidenceId()).isPresent(), false));
        }
        return new EvidenceShareDTO.ShareList(items);
    }

    private record Checked(@Nullable EvidenceShareDTO.Error error, EvidenceKind kind, boolean excerpt, List<Integer> selection,
                           @Nullable EvidenceShareDTO.Preview preview) {

        static Checked failed(EvidenceShareDTO.Error error) {
            return new Checked(error, null, false, List.of(), null);
        }
    }

    /** The rules shared by preview and share: shareable kind, valid item of this case, and for a capture the selection and the guard. */
    private Checked check(PunishmentEntity punishment, UUID evidenceId, @Nullable List<Integer> requested, @Nullable String appellant) {
        EvidenceItemEntity item = evidenceId == null ? null : this.items.findById(evidenceId).orElse(null);
        if (item == null || item.isStagedChatFile() || !punishment.getIdentifier().equals(item.getPunishmentIdentifier())) {
            return Checked.failed(error(CaseErrorKind.NOT_FOUND, "there is no such evidence in this case", "NOT_FOUND"));
        }
        boolean image = item.getKind() == EvidenceKind.FILE && item.getFileContentType() != null && item.getFileContentType().startsWith("image/");
        boolean capture = item.getKind() == EvidenceKind.CHAT_LOG;
        if ((!image && !capture) || item.getProvenance() == EvidenceProvenance.PLAYER) {
            return Checked.failed(error(CaseErrorKind.UNPROCESSABLE, "only a chat capture or a screenshot of the team can be shared", "NOT_SHAREABLE"));
        }
        if (item.getStatus() != EvidenceStatus.VALID) {
            return Checked.failed(error(CaseErrorKind.CONFLICT, "the evidence is " + item.getStatus() + " and cannot be shared", "NOT_VALID"));
        }
        if (image) {
            if (requested != null && !requested.isEmpty()) {
                return Checked.failed(error(CaseErrorKind.VALIDATION, "an image is shared whole: leave the selection empty", "BAD_SELECTION"));
            }
            Integer width = item.getPayload() != null && item.getPayload().get("width") instanceof Number n ? n.intValue() : null;
            Integer height = item.getPayload() != null && item.getPayload().get("height") instanceof Number n ? n.intValue() : null;
            return new Checked(null, item.getKind(), false, List.of(),
                    new EvidenceShareDTO.Preview("FILE", List.of(), item.getFileContentType(), width, height));
        }

        List<Map<String, Object>> lines = ExcerptRenderer.linesOf(item.getPayload());
        if (requested == null || requested.isEmpty() || requested.size() > this.maxLines) {
            return Checked.failed(error(CaseErrorKind.VALIDATION, "select 1 to " + this.maxLines + " lines", "BAD_SELECTION"));
        }
        TreeSet<Integer> sorted = new TreeSet<>(requested);
        if (sorted.size() != requested.size() || sorted.first() < 0 || sorted.last() >= lines.size()) {
            return Checked.failed(error(CaseErrorKind.VALIDATION, "the selection names a line twice or outside the capture", "BAD_SELECTION"));
        }
        if (appellant == null || appellant.isBlank()) {
            return Checked.failed(error(CaseErrorKind.VALIDATION, "appellantSpeaker is required: choose which speaker is the appealing player", "BAD_SELECTION"));
        }
        boolean appears = false;
        for (int index : sorted) {
            Map<String, Object> line = lines.get(index);
            if (ExcerptRenderer.speakerOf(line).equals(appellant)) {
                appears = true;
            }
            Object text = line.get("text");
            if (text != null && (PiiGuard.containsUuid(text.toString()) || PiiGuard.containsIp(text.toString()))) {
                return Checked.failed(error(CaseErrorKind.UNPROCESSABLE, "line " + index + " contains a UUID or an IP address", "PII_REJECTED"));
            }
        }
        if (!appears) {
            return Checked.failed(error(CaseErrorKind.VALIDATION, "appellantSpeaker is not a speaker of the selected lines", "BAD_SELECTION"));
        }
        List<Integer> selection = List.copyOf(sorted);
        List<EvidenceShareDTO.Line> rendered = ExcerptRenderer.render(lines, selection, appellant).stream()
                .map(line -> new EvidenceShareDTO.Line(line.speaker(), line.offsetSeconds(), line.text())).toList();
        return new Checked(null, item.getKind(), true, selection, new EvidenceShareDTO.Preview("EXCERPT", rendered, null, null, null));
    }

    private EvidenceShareDTO.Share toDto(EvidenceShareStore.Share share, boolean evidencePresent, boolean repeated) {
        EvidenceKind kind = this.items.findById(share.evidenceId()).map(EvidenceItemEntity::getKind)
                .orElse(share.mode().equals("EXCERPT") ? EvidenceKind.CHAT_LOG : EvidenceKind.FILE);
        String state = !share.active() ? "REVOKED" : evidencePresent ? "ACTIVE" : "UNAVAILABLE";
        ShareRevokeReason reason = share.revokeReason() == null ? null : ShareRevokeReason.valueOf(share.revokeReason());
        return new EvidenceShareDTO.Share(share.shareId(), share.evidenceId(), kind.name(), share.mode(),
                share.mode().equals("EXCERPT") ? share.selection().size() : null, state, share.sharedAt(), share.sharedByOid(),
                share.revokedAt(), reason, repeated);
    }

    private static EvidenceShareDTO.Error error(CaseErrorKind kind, String message, String code) {
        return new EvidenceShareDTO.Error(message, kind, code);
    }
}
