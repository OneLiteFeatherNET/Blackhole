package net.onelitefeather.blackhole.backend.casetimeline;

import java.util.List;
import java.util.UUID;

/**
 * The writes that are themselves case history: each is <b>one SQL statement</b> that changes the
 * state and appends its event together, so the two never diverge (no {@code @Transactional}, see
 * {@code JdbcEloScoreWriter} for the precedent). A new note is a plain append through
 * {@link CaseEventRepository}, the note being its own event.
 */
public interface CaseChanges {

    /** An event a change appended. */
    record Appended(UUID identifier, long seq, String type) {
    }

    /** Makes sure the punishment has a case row (a read never creates one). Idempotent. */
    void ensureCase(String punishmentIdentifier, long now);

    /**
     * Changes the metadata from {@code before} to {@code after} if the case is still at
     * {@code expectedVersion}, appending one event per changed field.
     *
     * @return the appended events, or empty when the version was stale and nothing changed
     * @throws IllegalArgumentException if {@code before} and {@code after} are equal
     */
    List<Appended> update(String punishmentIdentifier, long expectedVersion, long now, CaseActor actor, CaseMeta before, CaseMeta after);

    /**
     * Appends {@code note.edited} unless a version of the note was redacted in the meantime.
     *
     * @return the appended event, or {@code null} when the note is redacted
     */
    Appended editNote(String punishmentIdentifier, String noteId, String text, long now, CaseActor actor);

    /** A Ban ID leak that was marked just now, with whether an appeal had been filed. */
    record LeakMarked(Appended event, boolean afterFiling) {
    }

    /** A Ban ID that was reissued just now, with the generations it moved between. */
    record Reissued(Appended event, int fromGeneration, int toGeneration) {
    }

    /** A player message whose text was erased, with the {@code message.redacted} event that says so. */
    record Erased(String messageId, Appended event, String visibility) {
    }

    /**
     * Appends a message event and, when {@code promoteWaiting} is set and the case is {@code WAITING},
     * moves it to {@code IN_REVIEW} with a {@code SYSTEM} {@code case.status_changed}, all in one
     * statement. The case row is created if there is none. A draft whose dedupe key already exists
     * changes nothing.
     *
     * @return the appended events, the message first; empty when the message already existed
     */
    default List<Appended> appendMessage(CaseEventDraft message, boolean promoteWaiting) {
        return appendMessage(message, promoteWaiting, null);
    }

    /**
     * The staged chat files a message binds.
     *
     * @param attachmentIds the evidence items; each must be a staged chat file ({@code punishment_identifier} null) of this scope and of
     *                      the punishment's owner
     * @param scope         the staging scope ({@code chat:<appeal id>} for a player, {@code chat:<punishment>} for staff)
     */
    record Claim(List<UUID> attachmentIds, String scope) {
    }

    /**
     * The same, binding staged chat files to the punishment in the same statement. The items are locked ({@code FOR UPDATE}) and claimed
     * only when <b>every</b> named file is still staged for this scope; otherwise nothing is claimed and no message is appended, so a
     * concurrent message that took one of them first leaves this one empty-handed (the caller then reports a conflict). A draft whose
     * dedupe key exists claims nothing either: a repeat with the same {@code Idempotency-Key} changes nothing. The claimed items keep
     * their chat scope, which is how the files of an appeal are counted for the quota.
     *
     * @param claim the files to bind, or {@code null} for a message without files
     */
    List<Appended> appendMessage(CaseEventDraft message, boolean promoteWaiting, @io.micronaut.core.annotation.Nullable Claim claim);

    /**
     * Appends a {@code message.edited} unless a version of the message was redacted in the meantime or
     * the same edit number was already taken by a concurrent edit.
     *
     * @return the appended event, or {@code null} when nothing was appended
     */
    Appended editMessage(CaseEventDraft edit);

    /**
     * Marks the Ban ID of the punishment's current generation leaked: sets the leak state of the case
     * and appends {@code ban_id.leak_marked} in one statement. The case row must exist. (The player
     * sessions of the punishment are deleted in the same statement once the public API stores them.)
     *
     * @return the event, or {@code null} when the Ban ID was already marked leaked
     */
    default LeakMarked markBanIdLeaked(String punishmentIdentifier, BanIdLeakReason reason, BanIdLeakSource source, long now, CaseActor actor) {
        return markBanIdLeaked(punishmentIdentifier, reason, source, now, actor, null);
    }

    /** Who posted a leaked Ban ID, as an opaque participant, and whether that was the ticket's bound player. */
    record Poster(String participantId, boolean isOwner) {
    }

    /**
     * The same, naming the poster the Discord bot saw (the event records the participant id and whether the poster is
     * the bound player, never a Discord id).
     *
     * @param poster the poster, or {@code null} when staff marked the leak
     */
    LeakMarked markBanIdLeaked(String punishmentIdentifier, BanIdLeakReason reason, BanIdLeakSource source, long now, CaseActor actor,
                               @io.micronaut.core.annotation.Nullable Poster poster);

    /**
     * Replaces the punishment's Ban ID, raises its generation, clears the leak state and appends
     * {@code ban_id.reissued}, in one statement. The case row must exist.
     *
     * @param hash      the lookup hash of the new Ban ID
     * @param encrypted the encrypted form of the new Ban ID, bound to the punishment identifier
     * @return the event, or {@code null} when the punishment has no Ban ID yet
     * @throws BanIdCollisionException when another punishment already has this hash
     */
    Reissued reissueBanId(String punishmentIdentifier, byte[] hash, byte[] encrypted, BanIdReissueReason reason, long now, CaseActor actor);

    /**
     * The same, but only when the punishment is still at {@code expectedGeneration}, so a second replica or a
     * repeated run that raced this one changes nothing and appends no second event. The system's reissue after a
     * key rotation uses it.
     *
     * @return the event, or {@code null} when the punishment has no Ban ID or has moved past the generation
     * @throws BanIdCollisionException when another punishment already has this hash
     */
    Reissued reissueBanIdAtGeneration(String punishmentIdentifier, int expectedGeneration, byte[] hash, byte[] encrypted,
                                      BanIdReissueReason reason, long now, CaseActor actor);

    /**
     * Redacts every version of every message the player wrote and appends one {@code message.redacted}
     * per message, in one statement. Staff messages are untouched; a message that was redacted before
     * gets no second event.
     *
     * @param redactedByOid the admin who erased
     * @return the messages that got an event now
     */
    List<Erased> erasePlayerMessages(String punishmentIdentifier, String reason, long now, UUID redactedByOid, CaseActor actor);
}
