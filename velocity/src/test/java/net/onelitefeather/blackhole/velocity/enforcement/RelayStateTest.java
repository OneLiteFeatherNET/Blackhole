package net.onelitefeather.blackhole.velocity.enforcement;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup check of SignedVelocity; the error sink is a list the test owns, so no logging backend is needed. */
class RelayStateTest {

    @Test
    void aLoadedRelayIsPresentWithItsVersionAndLogsNothing() {
        List<String> errors = new ArrayList<>();

        RelayState state = RelayState.check(true, "1.5.0", errors::add);

        assertTrue(state.present(), "present");
        assertEquals("1.5.0", state.version(), "the version is reported when known");
        assertTrue(errors.isEmpty(), "nothing to report when the relay is there");
    }

    @Test
    void aMissingRelayIsAbsentWithoutAVersion() {
        RelayState state = RelayState.check(false, "1.5.0", message -> { });

        assertFalse(state.present(), "absent");
        assertNull(state.version(), "no version is reported for a relay that is not loaded");
    }

    @Test
    void aMissingRelayLogsAnErrorThatNamesTheConsequenceForMutes() {
        List<String> errors = new ArrayList<>();

        RelayState.check(false, null, errors::add);

        assertEquals(1, errors.size(), "exactly one error line");
        assertTrue(errors.getFirst().contains("mute"), "names mutes: " + errors.getFirst());
        assertTrue(errors.getFirst().contains("disconnect"), "names the disconnect: " + errors.getFirst());
    }
}
