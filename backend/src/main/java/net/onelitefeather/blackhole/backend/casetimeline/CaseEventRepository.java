package net.onelitefeather.blackhole.backend.casetimeline;

import java.util.UUID;

/**
 * The write side of the case event log, and deliberately all it can do: append, redact a note, and
 * delete everything of a punishment. A {@code case_event} row is otherwise never changed
 * ({@code CaseEventRepositorySurfaceTest} asserts that this interface does not grow).
 */
public interface CaseEventRepository {

    /**
     * Appends an event, creating the punishment's case first if it has none. A draft whose dedupe key
     * already exists inserts nothing.
     *
     * @return {@code true} if a row was inserted, {@code false} if the dedupe key already existed
     */
    boolean append(CaseEventDraft draft);

    /**
     * Redacts every version of a note ({@code note.added} and all {@code note.edited} of the subject):
     * the payload is removed, the type, time and actor stay.
     *
     * @return how many rows were redacted
     */
    int redact(String punishmentIdentifier, String subjectId, long redactedAt, UUID redactedByOid);

    /**
     * Deletes the case and all its events.
     *
     * @return how many events were deleted
     */
    int deleteForPunishment(String punishmentIdentifier);

    /**
     * Removes the display name snapshot from every {@code message.added} of a staff member (anonymization), the same key
     * removal the text redaction uses. The events and their other fields stay; a later protocol shows the legacy label.
     */
    void clearStaffNameSnapshots(UUID actorOid);
}
