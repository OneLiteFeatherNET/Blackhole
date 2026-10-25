package net.onelitefeather.blackhole.backend.staffprofile;

import java.util.Optional;

/** The cache of 64 px heads, one PNG per avatar key. */
public interface StaffAvatarRepository {

    Optional<byte[]> find(String avatarKey);

    /** Inserts or replaces the head of {@code avatarKey}. */
    void save(String avatarKey, byte[] png, long fetchedAt);

    void delete(String avatarKey);
}
