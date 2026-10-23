package net.onelitefeather.blackhole.velocity.component;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionsTest {

    private static Permissions with(String... granted) {
        return new Permissions(Set.of(granted)::contains);
    }

    @Test
    void unbanLetsTheReceiverUndoABan() {
        assertTrue(with(Permissions.UNBAN).mayUndo(EnforcementKind.BAN, false));
    }

    @Test
    void unbanLetsTheReceiverUndoAServerBan() {
        assertTrue(with(Permissions.UNBAN).mayUndo(EnforcementKind.SERVER, false));
    }

    @Test
    void unmuteLetsTheReceiverUndoAMute() {
        assertTrue(with(Permissions.UNMUTE).mayUndo(EnforcementKind.MUTE, false));
    }

    @Test
    void unbanDoesNotLetTheReceiverUndoAMute() {
        assertFalse(with(Permissions.UNBAN).mayUndo(EnforcementKind.MUTE, false), "a mute needs blackhole.unmute");
    }

    @Test
    void unmuteDoesNotLetTheReceiverUndoABan() {
        assertFalse(with(Permissions.UNMUTE).mayUndo(EnforcementKind.BAN, false), "a ban needs blackhole.unban");
    }

    @Test
    void aPermanentPunishmentNeedsThePardonPermissionToo() {
        assertFalse(with(Permissions.UNBAN).mayUndo(EnforcementKind.BAN, true), "unban alone is not enough");
        assertTrue(with(Permissions.UNBAN, Permissions.PARDON_PERMANENT).mayUndo(EnforcementKind.BAN, true));
    }

    @Test
    void thePardonPermissionAloneIsNotEnough() {
        assertFalse(with(Permissions.PARDON_PERMANENT).mayUndo(EnforcementKind.BAN, true), "the base permission is still needed");
    }

    @Test
    void withoutAnyPermissionNothingCanBeUndone() {
        for (EnforcementKind kind : EnforcementKind.values()) {
            assertFalse(with().mayUndo(kind, false), kind + " must not be undoable without permissions");
        }
    }
}
