package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileAdapterTest {

    private static PunishEntryDTO temporary(String id, String reason, long expiresAt) {
        UUID template = UUID.randomUUID();
        return new PunishEntryDTO().identifier(id).permanent(false).lifted(false)
                .template(new PunishTemplateResponse().identifier(template).reason(reason))
                .metaData(new HashMap<>(Map.of("expiration_date", expiresAt)));
    }

    private static PunishEntryDTO permanent(String id, String reason) {
        return new PunishEntryDTO().identifier(id).permanent(true).lifted(false).metaData(new HashMap<>(Map.of("reason", reason)));
    }

    @Test
    void aTemporaryPunishmentCarriesTheTemplateReasonAndItsExpiry() {
        var profile = new PunishProfileDTO().owner(OWNER).active(Map.of("network", temporary("p1", "<red>Hacking", 5000L)));

        PunishmentSyncMessage message = ProfileAdapter.activeMessages(OWNER, profile, standardRegistry()).getFirst();

        assertEquals("<red>Hacking", message.reason());
        assertEquals(5000L, message.expiresAt());
        assertEquals("network", message.section());
        assertEquals("BAN", message.enforcement(), "taken from the section as the proxy knows it");
        assertTrue(message.isSet());
    }

    @Test
    void aPermanentPunishmentWithoutTemplateUsesItsOwnReasonAndNeverExpires() {
        var profile = new PunishProfileDTO().owner(OWNER).active(Map.of("network", permanent("p1", "Permanently banned")));

        PunishmentSyncMessage message = ProfileAdapter.activeMessages(OWNER, profile, standardRegistry()).getFirst();

        assertEquals("Permanently banned", message.reason());
        assertNull(message.expiresAt(), "permanent means no expiry");
        assertNull(message.templateIdentifier());
    }

    @Test
    void aLiftedPunishmentIsNotActive() {
        var lifted = permanent("p1", "x").lifted(true);
        var profile = new PunishProfileDTO().owner(OWNER).active(Map.of("network", lifted));

        assertTrue(ProfileAdapter.activeMessages(OWNER, profile, standardRegistry()).isEmpty());
    }

    @Test
    void aProfileWithoutActivePunishmentsYieldsNothing() {
        assertEquals(List.of(), ProfileAdapter.activeMessages(OWNER, new PunishProfileDTO().owner(OWNER), standardRegistry()));
    }

    @Test
    void aSectionTheProxyDoesNotKnowHasNoEnforcement() {
        var profile = new PunishProfileDTO().owner(OWNER).active(Map.of("ghost", permanent("p1", "x")));

        assertNull(ProfileAdapter.activeMessages(OWNER, profile, standardRegistry()).getFirst().enforcement());
    }
}
