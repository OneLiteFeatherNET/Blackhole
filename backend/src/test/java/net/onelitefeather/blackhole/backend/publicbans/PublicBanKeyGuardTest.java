package net.onelitefeather.blackhole.backend.publicbans;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBanKeyGuardTest {

    @Test
    void unsetKeyIsRefusedOutsideLocalEnvironmentsWhenClientsAreConfigured() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PublicBanKeyGuard.check(null, "website", Set.of("prod")));
        assertTrue(e.getMessage().contains("BLACKHOLE_PUBLIC_BAN_KEY"), "the message names the variable");
    }

    @Test
    void unsetKeyIsAllowedWithoutClientsSoExistingDeploymentsStart() {
        assertDoesNotThrow(() -> PublicBanKeyGuard.check("", "", Set.of("prod")),
                "with no ban-list clients the key is not required");
    }

    @Test
    void unsetKeyIsAllowedInDevEvenWithClients() {
        assertDoesNotThrow(() -> PublicBanKeyGuard.check(null, "website", Set.of("dev")));
    }

    @Test
    void malformedKeyIsRefusedWithoutEchoingIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PublicBanKeyGuard.check("not-base64-!!", "", Set.of("prod")));
        assertFalse(e.getMessage().contains("not-base64-!!"), "the configured value is never echoed");
    }
}
