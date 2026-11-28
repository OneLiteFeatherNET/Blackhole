package net.onelitefeather.blackhole.backend.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationValidatorTest {

    private static final String CLIENT = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";

    private static IntegrationSpec valid() {
        return new IntegrationSpec("vulcan", "Vulcan bridge", CLIENT, true, List.of("DETECTION:1"), List.of("Lobby"), List.of());
    }

    @Test
    void aCompleteRegistrationHasNoProblems() {
        assertTrue(IntegrationValidator.validate(valid()).isEmpty());
    }

    @Test
    void aStaticServerAloneIsAValidScope() {
        IntegrationSpec spec = new IntegrationSpec("vulcan", "Vulcan", CLIENT, true, List.of("DETECTION:1"), List.of(), List.of("Creative"));
        assertTrue(IntegrationValidator.validate(spec).isEmpty());
    }

    @Test
    void anEmptyScopeIsRejected() {
        IntegrationSpec spec = new IntegrationSpec("vulcan", "Vulcan", CLIENT, true, List.of("DETECTION:1"), List.of(), List.of());
        assertFalse(IntegrationValidator.validate(spec).isEmpty(), "a registration must name at least one task or static server");
    }

    @Test
    void aSourceSystemWithUpperCaseIsRejected() {
        IntegrationSpec spec = new IntegrationSpec("Vulcan", "Vulcan", CLIENT, true, List.of("DETECTION:1"), List.of("Lobby"), List.of());
        assertFalse(IntegrationValidator.validate(spec).isEmpty());
    }

    @Test
    void aClientIdThatIsNotAGuidIsRejected() {
        IntegrationSpec spec = new IntegrationSpec("vulcan", "Vulcan", "not-a-guid", true, List.of("DETECTION:1"), List.of("Lobby"), List.of());
        assertFalse(IntegrationValidator.validate(spec).isEmpty());
    }

    @Test
    void aKindWithoutAVersionIsRejected() {
        IntegrationSpec spec = new IntegrationSpec("vulcan", "Vulcan", CLIENT, true, List.of("DETECTION"), List.of("Lobby"), List.of());
        assertFalse(IntegrationValidator.validate(spec).isEmpty());
    }

    @Test
    void noKindAtAllIsRejected() {
        IntegrationSpec spec = new IntegrationSpec("vulcan", "Vulcan", CLIENT, true, List.of(), List.of("Lobby"), List.of());
        assertFalse(IntegrationValidator.validate(spec).isEmpty());
    }

    @Test
    void aTaskNameWithASpaceIsRejected() {
        IntegrationSpec spec = new IntegrationSpec("vulcan", "Vulcan", CLIENT, true, List.of("DETECTION:1"), List.of("Lobby 2"), List.of());
        assertFalse(IntegrationValidator.validate(spec).isEmpty());
    }

    @Test
    void aBlankDisplayNameIsRejected() {
        IntegrationSpec spec = new IntegrationSpec("vulcan", "  ", CLIENT, true, List.of("DETECTION:1"), List.of("Lobby"), List.of());
        assertEquals(1, IntegrationValidator.validate(spec).stream().filter(e -> e.startsWith("displayName")).count());
    }
}
