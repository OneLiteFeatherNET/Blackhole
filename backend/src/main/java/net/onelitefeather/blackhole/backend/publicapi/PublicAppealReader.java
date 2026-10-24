package net.onelitefeather.blackhole.backend.publicapi;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;

import java.util.List;
import java.util.UUID;

/**
 * The appeals of one punishment as the player's own view needs them: plain rows, newest first, without the
 * statement, the decision note, the checklist or the reviewer. A staff-recorded {@code INELIGIBLE} attempt is
 * not part of the player's history.
 */
public interface PublicAppealReader {

    /**
     * @param legacyDuplicateOpen an open appeal that predates the one-open-appeal rule and is flagged for staff; it does not count as the open one
     */
    record Row(UUID identifier, AppealStatus status, long createdAt, long updatedAt, boolean legacyDuplicateOpen) {

        /** Whether the appeal is still open (submitted, pending or in review) and counts as the open one. */
        public boolean open() {
            return !this.legacyDuplicateOpen && (this.status == AppealStatus.SUBMITTED
                    || this.status == AppealStatus.ELIGIBLE_PENDING_REVIEW || this.status == AppealStatus.IN_REVIEW);
        }
    }

    /** The appeals of the punishment, newest first. */
    List<Row> list(String punishmentIdentifier);
}
