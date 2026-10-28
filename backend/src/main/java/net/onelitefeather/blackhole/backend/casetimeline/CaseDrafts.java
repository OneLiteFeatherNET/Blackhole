package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.AppealRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.EvidenceRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.KickRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.PunishmentRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.WarnRow;

/**
 * Builds the case events of the lifecycle facts, with their deterministic dedupe keys. Used by the
 * live flows and by the reconciler alike, so a repaired event looks like the one that was lost.
 */
public final class CaseDrafts {

    private CaseDrafts() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** {@code punishment.applied}; the scores are only known to the flow that deducted the cost. */
    public static CaseEventDraft applied(PunishmentRow row, @Nullable Integer scoreBefore, @Nullable Integer scoreAfter,
                                         CaseEventOrigin origin, long occurredAt) {
        CaseActor actor = row.appliedBy();
        CaseEventPayload.Applied payload = new CaseEventPayload.Applied(row.sectionKey(), row.stepIndex(), row.stepCount(), row.eloCost(),
                scoreBefore, scoreAfter, row.permanent(), actor.trust() == ActorTrust.SYSTEM,
                row.templateId() == null ? null : row.templateId().toString(), row.expiresAt());
        return new CaseEventDraft(row.identifier(), payload, occurredAt, origin, actor, null, "applied:" + row.identifier());
    }

    /** {@code punishment.revoked}, or {@code punishment.lifted} when a granted full-lift appeal ended it. */
    public static CaseEventDraft ended(PunishmentRow row, CaseActor actor, boolean approximate, CaseEventOrigin origin, long occurredAt) {
        if (row.liftingAppeal() != null) {
            return new CaseEventDraft(row.identifier(), new CaseEventPayload.Lifted(row.liftingAppeal().toString(), approximate),
                    occurredAt, origin, actor, row.liftingAppeal().toString(), "lifted:" + row.identifier());
        }
        return new CaseEventDraft(row.identifier(), new CaseEventPayload.Revoked(approximate), occurredAt, origin, actor, null,
                "revoked:" + row.identifier());
    }

    /** {@code punishment.retemplated}: an imported punishment was linked to a template; once per punishment and template. */
    public static CaseEventDraft retemplated(String punishmentIdentifier, String templateId, @Nullable String fromTemplateId,
                                             CaseActor actor, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.Retemplated(templateId, fromTemplateId), occurredAt, origin,
                actor, templateId, "retemplated:" + punishmentIdentifier + ":" + templateId);
    }

    /** {@code punishment.kicked}, once per kick; the delivery outcome is the kick's status, never a timeline event. */
    public static CaseEventDraft kicked(KickRow row, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.identifier(), new CaseEventPayload.Kicked(row.scope(), row.target(), row.eloCost()),
                occurredAt, origin, row.kickedBy(), null, "kicked:" + row.identifier());
    }

    /** {@code punishment.warned}, once per warning. */
    public static CaseEventDraft warned(WarnRow row, @Nullable String templateKey, @Nullable Integer eloCost,
                                        CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.identifier(), new CaseEventPayload.Warned(templateKey, eloCost), occurredAt, origin,
                row.warnedBy(), null, "warned:" + row.identifier());
    }

    /** {@code punishment.acknowledged}, once per warning: the player confirmed it. */
    public static CaseEventDraft acknowledged(String identifier, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(identifier, new CaseEventPayload.Acknowledged(), occurredAt, origin,
                CaseActor.player(), null, "acknowledged:" + identifier);
    }

    /** {@code punishment.expired}, always by the system. */
    public static CaseEventDraft expired(PunishmentRow row, long expiredAt, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.identifier(), new CaseEventPayload.Expired(expiredAt), occurredAt, origin,
                CaseActor.system(), null, "expired:" + row.identifier());
    }

    /** {@code appeal.filed}; filing records no actor. */
    public static CaseEventDraft appealFiled(AppealRow row, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.punishmentIdentifier(), new CaseEventPayload.AppealFiled(row.identifier().toString(), row.eligible()),
                occurredAt, origin, CaseActor.unattributed(), row.identifier().toString(), "appeal.filed:" + row.identifier());
    }

    /** {@code appeal.decided}; the decision note and statement stay in the appeal. */
    public static CaseEventDraft appealDecided(AppealRow row, @Nullable Long newExpirationAt, CaseEventOrigin origin, long occurredAt) {
        CaseEventPayload.AppealDecided payload = new CaseEventPayload.AppealDecided(row.identifier().toString(), row.status().name(), newExpirationAt);
        return new CaseEventDraft(row.punishmentIdentifier(), payload, occurredAt, origin, row.decidedByActor(),
                row.identifier().toString(), "appeal.decided:" + row.identifier());
    }

    /** {@code evidence.attached}: ids and enums only, no content and no file name. */
    public static CaseEventDraft evidenceAttached(EvidenceRow row, CaseActor actor, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.punishmentIdentifier(),
                new CaseEventPayload.EvidenceAttached(row.identifier().toString(), row.kind().name(), row.sourceSystem()),
                occurredAt, origin, actor, row.identifier().toString(), "evidence.attached:" + row.identifier());
    }

    /** {@code evidence.lifted} or {@code evidence.revoked}, whichever the item's status says. */
    public static CaseEventDraft evidenceStatusChanged(EvidenceRow row, CaseActor actor, boolean approximate, CaseEventOrigin origin, long occurredAt) {
        String id = row.identifier().toString();
        return switch (row.status()) {
            case LIFTED -> new CaseEventDraft(row.punishmentIdentifier(),
                    new CaseEventPayload.EvidenceLifted(id, row.liftedByAppeal() == null ? null : row.liftedByAppeal().toString(), approximate),
                    occurredAt, origin, actor, id, "evidence.lifted:" + id);
            case REVOKED -> new CaseEventDraft(row.punishmentIdentifier(), new CaseEventPayload.EvidenceRevoked(id, approximate),
                    occurredAt, origin, actor, id, "evidence.revoked:" + id);
            case VALID -> throw new IllegalArgumentException("a valid evidence item has no status change");
        };
    }

    /** The id a message gets when the caller sent an {@code Idempotency-Key}: the same key names the same message. */
    public static String idempotentMessageId(String punishmentIdentifier, String authorKey, String idempotencyKey) {
        return java.util.UUID.nameUUIDFromBytes(("message|" + punishmentIdentifier + "|" + authorKey + "|" + idempotencyKey)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** The id of the player message that comes with the decision of an appeal: one per appeal, so recording it twice inserts once. */
    public static String decisionMessageId(String appealId) {
        return java.util.UUID.nameUUIDFromBytes(("appeal.decided|" + appealId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** {@code message.added} by a staff member: visible to the player, whichever way the request was shaped. */
    public static CaseEventDraft staffMessage(String punishmentIdentifier, String messageId, @Nullable String appealId, String text,
                                              MessageChannel channel, CaseActor actor, CaseEventOrigin origin, long occurredAt) {
        return staffMessage(punishmentIdentifier, messageId, appealId, text, java.util.List.of(), channel, actor, origin, occurredAt);
    }

    /** The same with the chat files it sends (their payload entries); the text is empty for a message that is only files. */
    public static CaseEventDraft staffMessage(String punishmentIdentifier, String messageId, @Nullable String appealId, String text,
                                              java.util.List<MessageAttachment> attachments, MessageChannel channel, CaseActor actor,
                                              CaseEventOrigin origin, long occurredAt) {
        return staffMessage(punishmentIdentifier, messageId, appealId, text, attachments, channel, actor, origin, occurredAt, null);
    }

    /**
     * The same with the display name the staff member had when writing: a snapshot, so a later rename does not rewrite the
     * message. {@code staffDisplayName} is {@code null} for a message that must not carry a name.
     */
    public static CaseEventDraft staffMessage(String punishmentIdentifier, String messageId, @Nullable String appealId, String text,
                                              java.util.List<MessageAttachment> attachments, MessageChannel channel, CaseActor actor,
                                              CaseEventOrigin origin, long occurredAt, @Nullable String staffDisplayName) {
        return new CaseEventDraft(punishmentIdentifier,
                new CaseEventPayload.MessageAdded(messageId, appealId, MessageAuthorKind.STAFF, null, channel, text, attachments,
                        java.util.List.of(), staffDisplayName),
                occurredAt, origin, actor, messageId, "message:" + messageId, CaseEventDraft.PLAYER);
    }

    /** {@code message.added} by the player, from the portal: trust {@code PLAYER}, no {@code oid}. */
    public static CaseEventDraft playerMessage(String punishmentIdentifier, String messageId, @Nullable String appealId, String text,
                                               long occurredAt) {
        return playerMessage(punishmentIdentifier, messageId, appealId, text, java.util.List.of(), occurredAt);
    }

    /** The same with the chat files it sends (their payload entries); the text is empty for a message that is only files. */
    public static CaseEventDraft playerMessage(String punishmentIdentifier, String messageId, @Nullable String appealId, String text,
                                               java.util.List<MessageAttachment> attachments, long occurredAt) {
        return new CaseEventDraft(punishmentIdentifier,
                new CaseEventPayload.MessageAdded(messageId, appealId, MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, text, attachments,
                        java.util.List.of()),
                occurredAt, CaseEventOrigin.LIVE, CaseActor.player(), messageId, "message:" + messageId, CaseEventDraft.PLAYER);
    }

    /** {@code message.edited}, the {@code version}-th edit (1 for the first); visibility is the original's. */
    public static CaseEventDraft messageEdited(String punishmentIdentifier, String messageId, int version, String text, String visibility,
                                               CaseActor actor, long occurredAt) {
        return new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.MessageEdited(messageId, text), occurredAt, CaseEventOrigin.LIVE,
                actor, messageId, "message.edited:" + messageId + ":" + version, visibility);
    }

    /** {@code message.deleted}; the text of the earlier versions stays. */
    public static CaseEventDraft messageDeleted(String punishmentIdentifier, String messageId, MessageAuthorKind deletedBy, String visibility,
                                                CaseActor actor, long occurredAt) {
        return new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.MessageDeleted(messageId, deletedBy), occurredAt,
                CaseEventOrigin.LIVE, actor, messageId, "message.deleted:" + messageId, visibility);
    }

    /** {@code message.redacted}, once per message however often it is asked. */
    public static CaseEventDraft messageRedacted(String punishmentIdentifier, String messageId, String reason, String visibility,
                                                 CaseActor actor, long occurredAt) {
        return new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.MessageRedacted(messageId, reason), occurredAt,
                CaseEventOrigin.LIVE, actor, messageId, "message.redacted:" + messageId, visibility);
    }

    /**
     * {@code protocol.stored}: a protocol version was stored for an appeal. Recorded by the system, staff
     * visibility; one per appeal and version, so a retry or the reconciler inserts nothing twice.
     */
    public static CaseEventDraft protocolStored(String punishmentIdentifier, String appealId, int version, String evidenceId, int entries,
                                                String digest, long occurredAt) {
        return new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.ProtocolStored(appealId, version, evidenceId, entries, digest),
                occurredAt, CaseEventOrigin.LIVE, CaseActor.system(), appealId, "protocol:" + appealId + ":v" + version);
    }

    /** {@code transcript.exported}: who exported, in which format and how many entries; never content. */
    public static CaseEventDraft transcriptExported(String punishmentIdentifier, String format, int entries, boolean includeNotes,
                                                    CaseActor actor, long occurredAt) {
        return new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.TranscriptExported(format, entries, includeNotes), occurredAt,
                CaseEventOrigin.LIVE, actor, null, null);
    }
}
