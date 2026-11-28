package net.onelitefeather.blackhole.backend.exemption;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExemptionRankTest {

    @Test
    void theHighestRankOfASnapshotWins() {
        assertEquals(Optional.of(ExemptionRank.ADMIN), ExemptionRank.highest("ADMIN,MODERATOR,VIEWER"), "the admin outranks the rest");
    }

    @Test
    void aSnapshotWithoutAKnownRoleHasNoRank() {
        assertEquals(Optional.empty(), ExemptionRank.highest("UNKNOWN"), "an unknown role names no rank");
        assertEquals(Optional.empty(), ExemptionRank.highest(null), "no snapshot names no rank");
    }

    @Test
    void anAdminOutranksAModeratorAndAModeratorDoesNotOutrankAModerator() {
        assertTrue(ExemptionRank.ADMIN.outranks(ExemptionRank.MODERATOR), "an admin outranks a moderator");
        assertFalse(ExemptionRank.MODERATOR.outranks(ExemptionRank.MODERATOR), "equal ranks do not outrank each other");
    }
}
