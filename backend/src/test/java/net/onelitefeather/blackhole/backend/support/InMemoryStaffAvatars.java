package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.staffprofile.StaffAvatarRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A fresh in-memory head cache per test. */
public final class InMemoryStaffAvatars implements StaffAvatarRepository {

    private final Map<String, byte[]> rows = new HashMap<>();

    @Override
    public Optional<byte[]> find(String avatarKey) {
        return Optional.ofNullable(this.rows.get(avatarKey));
    }

    @Override
    public void save(String avatarKey, byte[] png, long fetchedAt) {
        this.rows.put(avatarKey, png);
    }

    @Override
    public void delete(String avatarKey) {
        this.rows.remove(avatarKey);
    }
}
