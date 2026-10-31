package net.onelitefeather.blackhole.velocity.guard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static net.onelitefeather.blackhole.velocity.guard.Refusals.withBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /guard} writes the level for the staff member who typed it, and tells them about a refusal. */
class GuardCommandTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");

    private final FakeGuardBackend backend = new FakeGuardBackend();
    private final GuardCommand command = new GuardCommand(this.backend);
    private final RecordingPlayer staff = new RecordingPlayer(STAFF, "Staff");

    private static String keyOf(Component message) {
        return ((TranslatableComponent) message).key();
    }

    @Test
    void aLevelChangeIsMadeForTheStaffMembersOwnUuid() {
        this.command.handle(this.staff.player(), "strict");

        assertEquals(List.of(new FakeGuardBackend.Write("level STRICT", STAFF)), this.backend.writes,
                "the backend attributes the change to the Minecraft UUID of the sender");
    }

    @Test
    void theLevelLineStartsWithThePrefix() {
        this.command.handle(this.staff.player(), "strict");

        assertTrue(this.staff.texts().getFirst().startsWith("Blackhole » "), this.staff.texts().toString());
    }

    @Test
    void aStaleStaffRoleTellsTheMemberToSignInToThePanel() {
        this.backend.writeFailure = withBody(403, "STAFF_ROLE_STALE: sign in to the team panel once, then try again", "FORBIDDEN");

        this.command.handle(this.staff.player(), "strict");

        assertEquals("guard.error.staff_role_stale", keyOf(this.staff.messages().getFirst()), "the stale role gets its own message");
    }

    @Test
    void aMemberWhoIsNotEligibleIsToldWhoMayChangeTheGuard() {
        this.backend.writeFailure = withBody(403, "STAFF_NOT_ELIGIBLE: the staff member has no live staff link", "FORBIDDEN");

        this.command.handle(this.staff.player(), "strict");

        assertEquals("guard.error.staff_not_eligible", keyOf(this.staff.messages().getFirst()), "a member without the role gets its own message");
    }

    @Test
    void anUnknownRefusalTellsTheMemberTheChangeWasNotSaved() {
        this.backend.writeFailure = withBody(500, "boom", "UNAVAILABLE");

        this.command.handle(this.staff.player(), "strict");

        assertEquals("guard.error.failed", keyOf(this.staff.messages().getFirst()), "an unexplained failure gets the generic message");
    }
}
