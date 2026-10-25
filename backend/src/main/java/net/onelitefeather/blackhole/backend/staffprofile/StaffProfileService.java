package net.onelitefeather.blackhole.backend.staffprofile;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.staffprofile.dto.StaffProfileDTO;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * A staff member's display name and avatar choice. The name is the staff member's own statement to players: no
 * other person sets it, and the Entra name of the sign-in is never copied in. Expected failures are answered with
 * the {@link StaffProfileDTO.Error} variant, not thrown.
 */
@Singleton
public class StaffProfileService {

    private static final String FORBIDDEN_MESSAGE = "only a signed-in staff member has a profile";

    private final StaffProfileRepository profiles;
    private final StaffAvatarJob avatars;
    private final Clock clock;

    public StaffProfileService(StaffProfileRepository profiles, StaffAvatarJob avatars, Clock clock) {
        this.profiles = profiles;
        this.avatars = avatars;
        this.clock = clock;
    }

    /** The caller's profile; an account without one has no name yet. */
    public StaffProfileDTO get(Actor caller) {
        if (caller.kind() != ActorKind.HUMAN) {
            return new StaffProfileDTO.Error(FORBIDDEN_MESSAGE, ErrorKind.FORBIDDEN);
        }
        return response(this.profiles.find(caller.oid()).orElse(null));
    }

    /**
     * Saves the caller's name and avatar choice.
     *
     * @return the {@link StaffProfileDTO.Response}, or an error: 403 for a service principal, 422 for a name that breaks a
     * rule ({@code NAME_NOT_ALLOWED}) or a missing notice acknowledgement ({@code NOTICE_REQUIRED}), 409 for a name another
     * staff member holds ({@code NAME_TAKEN}), 400 for an unknown avatar mode
     */
    public StaffProfileDTO save(StaffProfileDTO.UpdateRequest request, Actor caller) {
        if (caller.kind() != ActorKind.HUMAN) {
            return new StaffProfileDTO.Error(FORBIDDEN_MESSAGE, ErrorKind.FORBIDDEN);
        }
        Optional<StaffProfile> existing = this.profiles.find(caller.oid());
        String mode = request.avatarMode() == null ? existing.map(StaffProfile::avatarMode).orElse(StaffProfile.AUTO) : request.avatarMode();
        if (!StaffProfile.AUTO.equals(mode) && !StaffProfile.INITIALS.equals(mode)) {
            return new StaffProfileDTO.Error("avatarMode must be AUTO or INITIALS", ErrorKind.VALIDATION);
        }
        StaffNameRules.Verdict verdict = StaffNameRules.check(request.displayName());
        if (verdict instanceof StaffNameRules.Rejected rejected) {
            return new StaffProfileDTO.Error(reason(rejected.problem()), ErrorKind.NAME_NOT_ALLOWED);
        }
        StaffNameRules.Accepted accepted = (StaffNameRules.Accepted) verdict;
        boolean noticeBefore = existing.map(profile -> profile.noticeAcknowledgedAt() != null).orElse(false);
        if (!noticeBefore && !Boolean.TRUE.equals(request.noticeAcknowledged())) {
            return new StaffProfileDTO.Error("the staff notice must be acknowledged before the name is saved", ErrorKind.NOTICE_REQUIRED);
        }
        Optional<UUID> holder = this.profiles.findOidByFoldedName(accepted.folded());
        if (holder.isPresent() && !holder.get().equals(caller.oid())) {
            return new StaffProfileDTO.Error("another staff member already uses this name", ErrorKind.NAME_TAKEN);
        }
        long now = this.clock.millis();
        StaffProfile base = existing.orElseGet(() -> StaffProfile.empty(caller.oid(), now));
        Long noticeAt = noticeBefore ? base.noticeAcknowledgedAt() : now;
        StaffProfile next = new StaffProfile(caller.oid(), accepted.displayName(), accepted.folded(), mode,
                base.avatarKey(), noticeAt, now, null);
        try {
            this.profiles.save(next);
        } catch (StaffNameTakenException raced) {
            return new StaffProfileDTO.Error("another staff member already uses this name", ErrorKind.NAME_TAKEN);
        }
        return response(next);
    }

    /**
     * The name players see for a staff member, if one is set and the person is not anonymized. Writes to players
     * use it: without a name they are refused with {@code STAFF_PROFILE_REQUIRED}.
     */
    public Optional<String> namedDisplayName(UUID oid) {
        return this.profiles.find(oid)
                .filter(profile -> profile.anonymizedAt() == null)
                .map(StaffProfile::displayName);
    }

    /** Clears the name and the avatar of an anonymized staff member. The notice time and the row stay. */
    public void anonymize(UUID oid) {
        this.avatars.onUnlinked(oid);
        this.profiles.find(oid).ifPresent(profile -> this.profiles.save(profile.anonymized(this.clock.millis())));
    }

    private static StaffProfileDTO response(StaffProfile profile) {
        if (profile == null) {
            return new StaffProfileDTO.Response(null, StaffProfile.AUTO, null, false);
        }
        String key = StaffProfile.INITIALS.equals(profile.avatarMode()) ? null : profile.avatarKey();
        return new StaffProfileDTO.Response(profile.anonymizedAt() == null ? profile.displayName() : null,
                profile.avatarMode(), key, profile.noticeAcknowledgedAt() != null);
    }

    private static String reason(StaffNameRules.Problem problem) {
        return switch (problem) {
            case LENGTH -> "the name must be 2 to 32 characters";
            case CHARACTERS -> "the name may use letters, digits, spaces, hyphens, underscores, full stops and apostrophes only";
            case LOOKALIKE -> "the name must not look like a link, an address, an identifier or a ban ID";
            case RESERVED -> "this word is reserved for the team";
        };
    }
}
