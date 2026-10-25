package net.onelitefeather.blackhole.backend.staffprofile;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Produces and caches the head of a linked staff account. Called on link, unlink, anonymization and by the weekly
 * sweep. A failed fetch keeps the head that was cached before, and an account without one shows initials. Logs
 * name the avatar key only, never the UUID.
 */
@Singleton
public class StaffAvatarJob {

    private static final Logger LOG = LoggerFactory.getLogger(StaffAvatarJob.class);

    private final StaffProfileRepository profiles;
    private final StaffAvatarRepository avatars;
    private final StaffAvatarKeys keys;
    private final StaffAvatarSource source;
    private final Clock clock;

    public StaffAvatarJob(StaffProfileRepository profiles, StaffAvatarRepository avatars, StaffAvatarKeys keys,
                          StaffAvatarSource source, Clock clock) {
        this.profiles = profiles;
        this.avatars = avatars;
        this.keys = keys;
        this.source = source;
        this.clock = clock;
    }

    /** A staff account was linked to {@code minecraftUuid}: store its key and fetch the head. */
    public void onLinked(UUID oid, UUID minecraftUuid) {
        long now = this.clock.millis();
        String key = this.keys.keyFor(minecraftUuid);
        StaffProfile base = this.profiles.find(oid).orElseGet(() -> StaffProfile.empty(oid, now));
        if (base.avatarKey() != null && !base.avatarKey().equals(key)) {
            this.avatars.delete(base.avatarKey());
        }
        this.profiles.save(base.withAvatarKey(key, now));
        fetchInto(key, minecraftUuid, now);
    }

    /** A staff account was unlinked or anonymized: delete its head and clear the key. Repeating it is harmless. */
    public void onUnlinked(UUID oid) {
        this.profiles.find(oid).ifPresent(profile -> {
            if (profile.avatarKey() != null) {
                this.avatars.delete(profile.avatarKey());
                this.profiles.save(profile.withAvatarKey(null, this.clock.millis()));
            }
        });
    }

    /** The weekly refresh of one linked account. Does nothing when the profile no longer holds this account's key. */
    public void refresh(UUID oid, UUID minecraftUuid) {
        String key = this.keys.keyFor(minecraftUuid);
        boolean current = this.profiles.find(oid).map(StaffProfile::avatarKey).filter(key::equals).isPresent();
        if (current) {
            fetchInto(key, minecraftUuid, this.clock.millis());
        }
    }

    /** The cached head of an avatar key, if one exists. */
    public Optional<byte[]> png(String avatarKey) {
        return this.avatars.find(avatarKey);
    }

    private void fetchInto(String key, UUID minecraftUuid, long now) {
        try {
            Optional<byte[]> head = this.source.headPng(minecraftUuid);
            if (head.isPresent()) {
                this.avatars.save(key, head.get(), now);
            } else {
                LOG.info("No head for staff avatar {}; initials are shown", key);
            }
        } catch (RuntimeException e) {
            LOG.warn("Fetching the staff avatar {} failed; the cached head is kept: {}", key, e.getClass().getSimpleName());
        }
    }
}
