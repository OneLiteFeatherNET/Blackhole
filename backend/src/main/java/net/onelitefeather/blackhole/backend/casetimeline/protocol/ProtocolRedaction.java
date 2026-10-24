package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import java.util.UUID;

/**
 * What happens to stored protocols after a redaction or an erasure. The redaction statement itself
 * already marked every readable version of the punishment {@code PURGE_PENDING} (the old text is
 * unreadable at once); these calls delete the purged files and regenerate the protocol with markers and
 * anonymous authors. None throws: a failure is logged and counted, the redaction stays, and the
 * reconciler finishes the work.
 */
public interface ProtocolRedaction {

    /**
     * Message text of the punishment was redacted or erased (the statement marked the versions).
     */
    void afterRedaction(String punishmentIdentifier);

    /**
     * A participant of the punishment's conversation was erased: marks the versions, then purges and
     * regenerates. For the Discord change, which erases participants in its own statement.
     */
    void afterParticipantErased(String punishmentIdentifier);

    /**
     * A staff actor was anonymised (the display name is already cleared): marks the versions that may
     * show the name, then purges and regenerates.
     */
    void afterStaffAnonymized(UUID actorOid);
}
