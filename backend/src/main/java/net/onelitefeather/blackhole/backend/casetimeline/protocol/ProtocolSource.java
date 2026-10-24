package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the protocol is generated from, and nothing else: the appeal rows and the case events of
 * its punishment. There is no way to reach Discord or any other system from here, which is the point:
 * the protocol equals the record.
 */
public interface ProtocolSource {

    /**
     * The facts of an appeal the protocol needs.
     *
     * @param owner     the hashed owner of the punishment, for domain events only
     * @param createdAt when the appeal was filed
     * @param decidedAt when it was last updated; for a decided appeal, the decision
     */
    record Appeal(UUID identifier, String punishmentIdentifier, String owner, AppealStatus status, long createdAt, long decidedAt) {

        /** Whether a reviewer decided the appeal. */
        public boolean decided() {
            return this.status == AppealStatus.GRANTED_FULL_LIFT || this.status == AppealStatus.GRANTED_DURATION_REDUCTION
                    || this.status == AppealStatus.DENIED;
        }
    }

    /** The appeal, empty when there is none. */
    Optional<Appeal> appeal(UUID appealId);

    /** Every appeal of the punishment, oldest first. */
    List<Appeal> appealsOf(String punishmentIdentifier);

    /** Every event of the punishment's case with its actor's display name, in timeline order. */
    List<CaseReader.Entry> entries(String punishmentIdentifier);
}
