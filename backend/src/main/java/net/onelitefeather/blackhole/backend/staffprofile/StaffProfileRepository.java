package net.onelitefeather.blackhole.backend.staffprofile;

import java.util.Optional;
import java.util.UUID;

/** Storage of staff profiles. The database enforces the folded-name uniqueness; see {@link StaffNameTakenException}. */
public interface StaffProfileRepository {

    Optional<StaffProfile> find(UUID oid);

    /** The staff member who uses this folded name, if any. */
    Optional<UUID> findOidByFoldedName(String displayNameFolded);

    /**
     * Inserts or replaces the profile of {@code profile.oid()}.
     *
     * @throws StaffNameTakenException when another profile already holds the folded name
     */
    void save(StaffProfile profile);
}
