package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.staffprofile.StaffNameTakenException;
import net.onelitefeather.blackhole.backend.staffprofile.StaffProfile;
import net.onelitefeather.blackhole.backend.staffprofile.StaffProfileRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A fresh in-memory profile store per test; enforces the folded-name uniqueness like the partial index does. */
public final class InMemoryStaffProfiles implements StaffProfileRepository {

    private final Map<UUID, StaffProfile> rows = new HashMap<>();

    @Override
    public Optional<StaffProfile> find(UUID oid) {
        return Optional.ofNullable(this.rows.get(oid));
    }

    @Override
    public Optional<UUID> findOidByFoldedName(String folded) {
        return this.rows.values().stream()
                .filter(row -> folded.equals(row.displayNameFolded()))
                .map(StaffProfile::oid)
                .findFirst();
    }

    @Override
    public void save(StaffProfile profile) {
        if (profile.displayNameFolded() != null) {
            Optional<UUID> holder = findOidByFoldedName(profile.displayNameFolded());
            if (holder.isPresent() && !holder.get().equals(profile.oid())) {
                throw new StaffNameTakenException();
            }
        }
        this.rows.put(profile.oid(), profile);
    }
}
