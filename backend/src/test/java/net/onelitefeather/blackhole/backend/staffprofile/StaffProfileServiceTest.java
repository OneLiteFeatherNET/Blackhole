package net.onelitefeather.blackhole.backend.staffprofile;

import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.staffprofile.dto.StaffProfileDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAvatars;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffProfiles;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The staff display name rules as the service applies them. Fixed clock, in-memory fakes, no network. */
class StaffProfileServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID MIA = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MAX = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Actor MIA_ACTOR = new Actor(MIA, "Max Muster", ActorKind.HUMAN, false);
    private static final Actor MAX_ACTOR = new Actor(MAX, "Max Muster", ActorKind.HUMAN, false);

    private final InMemoryStaffProfiles profiles = new InMemoryStaffProfiles();
    private final InMemoryStaffAvatars avatars = new InMemoryStaffAvatars();
    private final StaffProfileService service = new StaffProfileService(
            this.profiles, new StaffAvatarJob(this.profiles, this.avatars, new StaffAvatarKeys("unit-test-secret"),
            new NoSource(), CLOCK), CLOCK);

    @Test
    void savingANameWithTheNoticeAcknowledgedStoresIt() {
        StaffProfileDTO result = this.service.save(update("Mia K.", "AUTO", true), MIA_ACTOR);

        assertEquals("Mia K.", assertInstanceOf(StaffProfileDTO.Response.class, result).displayName());
        assertEquals("Mia K.", this.service.namedDisplayName(MIA).orElseThrow());
    }

    @Test
    void aFoldedDuplicateOfAnotherStaffMembersNameAnswersNameTaken() {
        this.service.save(update("Mia K.", "AUTO", true), MIA_ACTOR);

        StaffProfileDTO result = this.service.save(update("MIA K.", "AUTO", true), MAX_ACTOR);

        StaffProfileDTO.Error error = assertInstanceOf(StaffProfileDTO.Error.class, result);
        assertEquals(ErrorKind.NAME_TAKEN, error.kind());
        assertNull(this.service.namedDisplayName(MAX).orElse(null));
    }

    @Test
    void aStaffMemberMayChangeTheCaseOfItsOwnName() {
        this.service.save(update("Mia K.", "AUTO", true), MIA_ACTOR);

        StaffProfileDTO result = this.service.save(update("MIA K.", "AUTO", true), MIA_ACTOR);

        assertEquals("MIA K.", assertInstanceOf(StaffProfileDTO.Response.class, result).displayName());
    }

    @Test
    void aReservedWordAnswersNameNotAllowedAndStoresNothing() {
        StaffProfileDTO result = this.service.save(update("Moderation", "AUTO", true), MIA_ACTOR);

        assertEquals(ErrorKind.NAME_NOT_ALLOWED, assertInstanceOf(StaffProfileDTO.Error.class, result).kind());
        assertFalse(this.service.namedDisplayName(MIA).isPresent());
    }

    @Test
    void aSaveWithoutTheNoticeAnswersNoticeRequiredAndStoresNothing() {
        StaffProfileDTO result = this.service.save(update("Mia K.", "AUTO", false), MIA_ACTOR);

        assertEquals(ErrorKind.NOTICE_REQUIRED, assertInstanceOf(StaffProfileDTO.Error.class, result).kind());
        assertFalse(this.service.namedDisplayName(MIA).isPresent());
    }

    @Test
    void theEntraNameIsNeverTheDefaultDisplayName() {
        StaffProfileDTO result = this.service.get(MIA_ACTOR);

        assertNull(assertInstanceOf(StaffProfileDTO.Response.class, result).displayName());
        assertFalse(this.service.namedDisplayName(MIA).isPresent());
    }

    @Test
    void aServicePrincipalIsRefusedAProfile() {
        Actor service = new Actor(MIA, null, ActorKind.SERVICE, true);

        StaffProfileDTO result = this.service.save(update("Mia K.", "AUTO", true), service);

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(StaffProfileDTO.Error.class, result).kind());
    }

    @Test
    void anUnknownAvatarModeIsRejected() {
        StaffProfileDTO result = this.service.save(update("Mia K.", "PHOTO", true), MIA_ACTOR);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(StaffProfileDTO.Error.class, result).kind());
    }

    @Test
    void anonymizingClearsTheNameAndMarksTheProfile() {
        this.service.save(update("Mia K.", "AUTO", true), MIA_ACTOR);

        this.service.anonymize(MIA);

        assertFalse(this.service.namedDisplayName(MIA).isPresent());
        assertTrue(this.profiles.find(MIA).orElseThrow().anonymizedAt() != null);
        assertFalse(this.profiles.findOidByFoldedName(StaffNameRules.fold("Mia K.")).isPresent());
    }

    private static StaffProfileDTO.UpdateRequest update(String name, String mode, boolean notice) {
        return new StaffProfileDTO.UpdateRequest(name, mode, notice);
    }

    /** A source that is never asked: the service tests do not fetch heads. */
    static final class NoSource implements StaffAvatarSource {
        @Override
        public Optional<byte[]> headPng(UUID minecraftUuid) {
            throw new AssertionError("the profile service must not fetch a head");
        }
    }
}
