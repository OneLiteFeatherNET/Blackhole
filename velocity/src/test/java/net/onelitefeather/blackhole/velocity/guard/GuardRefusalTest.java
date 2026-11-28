package net.onelitefeather.blackhole.velocity.guard;

import org.junit.jupiter.api.Test;

import static net.onelitefeather.blackhole.velocity.guard.Refusals.withBody;
import static net.onelitefeather.blackhole.velocity.guard.Refusals.withoutBody;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The backend's refusals, read back into the reason the player is told about. */
class GuardRefusalTest {

    @Test
    void aStaleStaffRoleIsReadFromTheCodeInTheMessage() {
        assertEquals(GuardRefusal.STAFF_ROLE_STALE,
                GuardRefusal.of(withBody(403, "STAFF_ROLE_STALE: sign in to the team panel once, then try again", "FORBIDDEN")));
    }

    @Test
    void aStaffMemberWhoIsNotEligibleIsReadFromTheCodeInTheMessage() {
        assertEquals(GuardRefusal.STAFF_NOT_ELIGIBLE,
                GuardRefusal.of(withBody(403, "STAFF_NOT_ELIGIBLE: the staff member has no live staff link", "FORBIDDEN")));
    }

    @Test
    void aCountryRefusalIsReadFromTheKind() {
        assertEquals(GuardRefusal.FORBIDDEN_KIND, GuardRefusal.of(withBody(403, "only an admin may ban a country", "FORBIDDEN_KIND")));
    }

    @Test
    void aDurationRefusalIsReadFromTheKind() {
        assertEquals(GuardRefusal.FORBIDDEN_DURATION,
                GuardRefusal.of(withBody(403, "a moderator may ban for up to seven days only", "FORBIDDEN_DURATION")));
    }

    @Test
    void aConflictIsReadFromTheKind() {
        assertEquals(GuardRefusal.CONFLICT, GuardRefusal.of(withBody(409, "this area is already banned", "CONFLICT")));
    }

    @Test
    void aValidationErrorIsReadFromTheKind() {
        assertEquals(GuardRefusal.VALIDATION, GuardRefusal.of(withBody(400, "reason must be 1 to 200 characters", "VALIDATION")));
    }

    @Test
    void aConflictWithoutABodyIsStillAConflict() {
        assertEquals(GuardRefusal.CONFLICT, GuardRefusal.of(withoutBody(409)));
    }

    @Test
    void aForbiddenWithoutACodeIsAnUnknownRefusal() {
        assertEquals(GuardRefusal.OTHER, GuardRefusal.of(withBody(403, "forbidden", "FORBIDDEN")));
    }

    @Test
    void aBackendFailureWithAPlainBodyIsAnUnknownRefusal() {
        assertEquals(GuardRefusal.OTHER, GuardRefusal.of(withoutBody(500)));
    }
}
