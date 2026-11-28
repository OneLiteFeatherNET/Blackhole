package net.onelitefeather.blackhole.velocity.guard;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardHeartbeatCounterTest {

    @Test
    void countsOnlyTheOnlinePlayersHoldingTheStaffNode() {
        List<Player> online = List.of(
                StaffPlayers.with(UUID.fromString("00000000-0000-0000-0000-000000000001"), Permissions.STAFF),
                StaffPlayers.with(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                StaffPlayers.with(UUID.fromString("00000000-0000-0000-0000-000000000003"), Permissions.STAFF));

        assertEquals(2, GuardHeartbeatCounter.staffOnline(online), "two online players hold the staff node");
    }

    @Test
    void aPlayerWhoLeavesIsNoLongerCounted() {
        Player staff = StaffPlayers.with(UUID.fromString("00000000-0000-0000-0000-000000000001"), Permissions.STAFF);

        assertEquals(1, GuardHeartbeatCounter.staffOnline(List.of(staff)), "the staff member is counted while online");
        assertEquals(0, GuardHeartbeatCounter.staffOnline(List.of()), "the staff member who left is not counted");
    }

    @Test
    void anEmptyProxyReportsZero() {
        assertEquals(0, GuardHeartbeatCounter.staffOnline(List.of()), "an empty proxy reports no staff");
    }
}
