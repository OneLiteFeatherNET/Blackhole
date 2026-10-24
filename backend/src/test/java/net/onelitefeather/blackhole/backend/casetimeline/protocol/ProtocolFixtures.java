package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds stored case events as the protocol sees them, without a database. */
final class ProtocolFixtures {

    static final String PUNISHMENT = "punishment-1";
    static final String OWNER = "ab".repeat(64);
    static final UUID APPEAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID STAFF_OID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private ProtocolFixtures() {
    }

    static ProtocolSource.Appeal appeal(UUID id, AppealStatus status, long createdAt, long decidedAt) {
        return new ProtocolSource.Appeal(id, PUNISHMENT, OWNER, status, createdAt, decidedAt);
    }

    static CaseEventRecord event(long seq, long at, String type, ActorTrust trust, String subject, String visibility, Map<String, Object> body) {
        return new CaseEventRecord(UUID.nameUUIDFromBytes(("event-" + seq).getBytes()), seq, PUNISHMENT, type, 1, at, CaseEventOrigin.LIVE, trust,
                trust == ActorTrust.VERIFIED ? STAFF_OID : null, null, null, subject, visibility, body, null, null);
    }

    static CaseReader.Entry entry(CaseEventRecord event, String actorName) {
        return new CaseReader.Entry(event, actorName);
    }

    static CaseReader.Entry playerMessage(long seq, long at, String id, UUID appeal, String text) {
        return entry(event(seq, at, "message.added", ActorTrust.PLAYER, id, "PLAYER",
                new CaseEventPayload.MessageAdded(id, appeal == null ? null : appeal.toString(), MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, text).toMap()), null);
    }

    static CaseReader.Entry staffMessage(long seq, long at, String id, UUID appeal, String text, String name) {
        return entry(event(seq, at, "message.added", ActorTrust.VERIFIED, id, "PLAYER",
                new CaseEventPayload.MessageAdded(id, appeal == null ? null : appeal.toString(), MessageAuthorKind.STAFF, null, MessageChannel.PANEL, text).toMap()), name);
    }

    /** A message another system wrote (a Discord bot), visible to staff only. */
    static CaseReader.Entry botMessage(long seq, long at, String id, UUID appeal, String text, String participant, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(
                new CaseEventPayload.MessageAdded(id, appeal.toString(), MessageAuthorKind.BOT, participant, MessageChannel.DISCORD, text).toMap());
        body.putAll(extra);
        return entry(event(seq, at, "message.added", ActorTrust.UNVERIFIED, id, "STAFF", body), null);
    }

    static CaseReader.Entry edited(long seq, long at, String id, String text, String name) {
        return entry(event(seq, at, "message.edited", ActorTrust.VERIFIED, id, "PLAYER", new CaseEventPayload.MessageEdited(id, text).toMap()), name);
    }

    static CaseReader.Entry deleted(long seq, long at, String id, String name) {
        return entry(event(seq, at, "message.deleted", ActorTrust.VERIFIED, id, "PLAYER",
                new CaseEventPayload.MessageDeleted(id, MessageAuthorKind.STAFF).toMap()), name);
    }

    /** The message.redacted marker. */
    static CaseReader.Entry redactedMarker(long seq, long at, String id) {
        return entry(event(seq, at, "message.redacted", ActorTrust.VERIFIED, id, "PLAYER",
                new CaseEventPayload.MessageRedacted(id, "OTHER").toMap()), "Admin");
    }

    /** The same row after a redaction: no text, redacted_at set. */
    static CaseReader.Entry redacted(CaseReader.Entry entry) {
        CaseEventRecord e = entry.event();
        Map<String, Object> body = new LinkedHashMap<>(e.body());
        body.remove("text");
        body.remove("attachments");
        body.remove("embeds");
        return new CaseReader.Entry(new CaseEventRecord(e.identifier(), e.seq(), e.punishmentIdentifier(), e.type(), e.payloadVersion(), e.occurredAt(),
                e.origin(), e.actorTrust(), e.actorOid(), e.recordedByOid(), e.actorSource(), e.subjectId(), e.visibility(), body, 99L, e.dedupeKey()),
                entry.actorName());
    }

    static CaseReader.Entry filed(long seq, long at, UUID appeal) {
        return entry(event(seq, at, "appeal.filed", ActorTrust.UNVERIFIED, appeal.toString(), "STAFF",
                new CaseEventPayload.AppealFiled(appeal.toString(), true).toMap()), null);
    }

    static CaseReader.Entry decided(long seq, long at, UUID appeal, String decision) {
        return entry(event(seq, at, "appeal.decided", ActorTrust.VERIFIED, appeal.toString(), "STAFF",
                new CaseEventPayload.AppealDecided(appeal.toString(), decision, null).toMap()), "Reviewer Rita");
    }

    static CaseReader.Entry note(long seq, long at, String text) {
        return entry(event(seq, at, "note.added", ActorTrust.VERIFIED, "note-" + seq, "STAFF", new CaseEventPayload.NoteAdded("note-" + seq, text).toMap()), "Reviewer Rita");
    }

    static CaseReader.Entry raw(long seq, long at, String type, ActorTrust trust, Map<String, Object> body) {
        return entry(event(seq, at, type, trust, null, "STAFF", body), null);
    }

    /** A small decided appeal: filed, a player message, a staff reply, the decision. */
    static List<CaseReader.Entry> simpleConversation() {
        return List.of(
                filed(1, 1_000, APPEAL),
                playerMessage(2, 2_000, "m-1", APPEAL, "I did not cheat."),
                staffMessage(3, 3_000, "m-2", APPEAL, "We looked at the logs.", "Reviewer Rita"),
                decided(4, 4_000, APPEAL, "DENIED"));
    }
}
