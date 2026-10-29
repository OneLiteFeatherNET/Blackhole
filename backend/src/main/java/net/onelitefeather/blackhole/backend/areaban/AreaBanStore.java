package net.onelitefeather.blackhole.backend.areaban;

import java.util.Optional;
import java.util.UUID;

/** Area ban records. At most one row per area is not lifted at a time. */
public interface AreaBanStore {

    /**
     * Inserts a ban.
     *
     * @return false when an unlifted ban already exists for the same area
     */
    boolean insert(AreaBan ban);

    Optional<AreaBan> findById(UUID id);

    /** Every unlifted ban, whether or not it has expired. Read by the in-memory snapshot. */
    java.util.List<AreaBan> listUnlifted();

    /** The unlifted ban of an area, whether or not it has expired. */
    Optional<AreaBan> findUnlifted(AreaBan.Kind kind, String value);

    /**
     * Marks an unlifted ban as lifted.
     *
     * @return false when the ban is missing or already lifted
     */
    boolean lift(AreaBan lifted);
}
