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
}
