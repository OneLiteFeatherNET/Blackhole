package net.onelitefeather.blackhole.velocity.guard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.onelitefeather.blackhole.client.model.AreaBanResponse;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static net.onelitefeather.blackhole.velocity.guard.Refusals.withBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /areaban} writes and lifts bans for the staff member who typed it, and tells them about a refusal. */
class AreaBanCommandTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");

    private final FakeGuardBackend backend = new FakeGuardBackend();
    private final AreaBanCommand command = new AreaBanCommand(this.backend);
    private final RecordingPlayer staff = new RecordingPlayer(STAFF, "Staff");

    private static String keyOf(Component message) {
        return ((TranslatableComponent) message).key();
    }

    @Test
    void anAsnBanIsMadeForTheStaffMembersOwnUuid() {
        this.command.handle(this.staff.player(), "asn 16276 1d spam");

        assertEquals(List.of(new FakeGuardBackend.Write("ban ASN 16276", STAFF)), this.backend.writes,
                "the backend attributes the ban to the Minecraft UUID of the sender");
    }

    @Test
    void aLiftIsMadeForTheStaffMembersOwnUuid() {
        this.backend.activeBans.add(new AreaBanResponse().id("ban-7").kind("ASN").value("16276").reason("spam")
                .source("PROXY").createdAt(0L).active(true));

        this.command.handle(this.staff.player(), "lift asn 16276 appeal accepted");

        assertEquals(List.of(new FakeGuardBackend.Write("lift ban-7", STAFF)), this.backend.writes,
                "the backend attributes the lift to the Minecraft UUID of the sender");
    }

    @Test
    void aModeratorsPermanentBanIsRefusedWithTheDurationMessage() {
        this.backend.writeFailure = withBody(403, "a moderator may ban for up to seven days only", "FORBIDDEN_DURATION");

        this.command.handle(this.staff.player(), "asn 16276 spam");

        assertEquals("guard.error.forbidden_duration", keyOf(this.staff.messages().getFirst()),
                "a refused duration tells the member the limit");
    }

    @Test
    void aStaleStaffRoleTellsTheMemberToSignInToThePanel() {
        this.backend.writeFailure = withBody(403, "STAFF_ROLE_STALE: sign in to the team panel once, then try again", "FORBIDDEN");

        this.command.handle(this.staff.player(), "asn 16276 1d spam");

        assertEquals("guard.error.staff_role_stale", keyOf(this.staff.messages().getFirst()), "the stale role gets its own message");
    }

    @Test
    void theBanConfirmationStartsWithThePrefix() {
        this.command.handle(this.staff.player(), "asn 16276 1d spam");

        assertTrue(this.staff.texts().getFirst().startsWith("Blackhole » "), this.staff.texts().toString());
    }

    @Test
    void theLiftConfirmationStartsWithThePrefix() {
        this.backend.activeBans.add(new AreaBanResponse().id("ban-7").kind("ASN").value("16276").reason("spam")
                .source("PROXY").createdAt(0L).active(true));

        this.command.handle(this.staff.player(), "lift asn 16276 appeal accepted");

        assertTrue(this.staff.texts().getFirst().startsWith("Blackhole » "), this.staff.texts().toString());
    }

    @Test
    void anInvalidAreaBanLineStartsWithThePrefix() {
        this.command.handle(this.staff.player(), "");

        assertTrue(this.staff.texts().getFirst().startsWith("Blackhole » "), this.staff.texts().toString());
    }
}
