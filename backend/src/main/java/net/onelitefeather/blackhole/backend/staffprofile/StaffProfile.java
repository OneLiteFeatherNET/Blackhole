package net.onelitefeather.blackhole.backend.staffprofile;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/**
 * One staff member's display name and avatar choice, as stored in {@code staff_profile}. Staff personal data,
 * kept while the person is staff; anonymizing clears the name and the avatar key.
 *
 * @param oid                  the staff member (an Entra object id)
 * @param displayName          the name players see, or {@code null} while none is set
 * @param displayNameFolded    the comparison form of {@code displayName}, unique among named profiles
 * @param avatarMode           {@link #AUTO} (the head when linked) or {@link #INITIALS}
 * @param avatarKey            the key of the cached head, {@code null} while the account is not linked
 * @param noticeAcknowledgedAt when the staff notice was acknowledged, epoch millis
 * @param updatedAt            when the row last changed, epoch millis
 * @param anonymizedAt         when the person was anonymized, epoch millis
 */
public record StaffProfile(
        UUID oid,
        @Nullable String displayName,
        @Nullable String displayNameFolded,
        String avatarMode,
        @Nullable String avatarKey,
        @Nullable Long noticeAcknowledgedAt,
        long updatedAt,
        @Nullable Long anonymizedAt
) {

    /** The head of the linked account, when there is one. */
    public static final String AUTO = "AUTO";
    /** Initials only, whatever the account is linked to. */
    public static final String INITIALS = "INITIALS";

    /** A profile without a name, avatar or notice: the default of every staff member. */
    public static StaffProfile empty(UUID oid, long now) {
        return new StaffProfile(oid, null, null, AUTO, null, null, now, null);
    }

    public StaffProfile withAvatarKey(@Nullable String key, long now) {
        return new StaffProfile(this.oid, this.displayName, this.displayNameFolded, this.avatarMode, key,
                this.noticeAcknowledgedAt, now, this.anonymizedAt);
    }

    /** The profile after anonymization: no name, no avatar, marked. The notice time stays as it was. */
    public StaffProfile anonymized(long now) {
        return new StaffProfile(this.oid, null, null, this.avatarMode, null, this.noticeAcknowledgedAt, now, now);
    }
}
