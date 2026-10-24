package net.onelitefeather.blackhole.backend.evidence.share;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where shares live. Every change is <b>one statement</b> that changes the row and appends its case event together (no
 * {@code @Transactional}), so the state and the history never diverge.
 */
public interface EvidenceShareStore {

    /**
     * A share row.
     *
     * @param selection        the shared line indexes, ascending; empty for a file
     * @param appellantSpeaker the speaker label that is "You"; {@code null} for a file
     */
    record Share(
            UUID shareId, String punishmentIdentifier, UUID appealId, UUID evidenceId, String mode, List<Integer> selection,
            @Nullable String appellantSpeaker, boolean confirmedMinimised, UUID sharedByOid, long sharedAt,
            @Nullable Long revokedAt, @Nullable UUID revokedByOid, @Nullable String revokeReason
    ) {

        public boolean active() {
            return this.revokedAt == null;
        }
    }

    /** The result of a share: the row, the event that was appended now ({@code null} for a repeat) and whether it was a repeat. */
    record Created(Share share, @Nullable CaseChanges.Appended event, boolean repeated) {
    }

    /**
     * Inserts the share and appends {@code event} in one statement, unless an active share of the same appeal, item and selection exists:
     * then nothing is inserted and that share is returned as a repeat. The case row must exist.
     */
    Created create(Share share, CaseEventDraft event);

    /**
     * Marks the share revoked and appends {@code event} in one statement.
     *
     * @return the event, or empty when the share is unknown, of another case, or revoked already (nothing changed)
     */
    Optional<CaseChanges.Appended> revoke(UUID shareId, String punishmentIdentifier, long now, UUID revokedByOid, String reason, CaseEventDraft event);

    Optional<Share> find(UUID shareId, String punishmentIdentifier);

    /** Every share of the case, oldest first. */
    List<Share> list(String punishmentIdentifier);

    /** The newest appeal of the punishment that was filed as a real appeal (not an ineligible attempt). */
    Optional<UUID> latestAppeal(String punishmentIdentifier);
}
