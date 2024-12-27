package net.onelitefeather.blackhole.api.profile;

import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.api.punish.PunishType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PunishProfileTest {

    @Test
    void testInitialProfileCreation() {
        PunishProfile profile = PunishProfile.builder()
                .owner("TestOwner")
                .activeBan(null)
                .activeChatBan(null)
                .metaData(new HashMap<>())
                .build();

        assertEquals("TestOwner", profile.owner());
        assertFalse(profile.activeBan().isPresent());
        assertFalse(profile.activeChatBan().isPresent());
        assertTrue(profile.history().isEmpty());
        assertFalse(profile.metaData().isEmpty());
    }

    @Test
    void testMetaDataFlow() {
        PunishProfile profile = PunishProfile.builder()
                .owner("TestOwner")
                .activeBan(null)
                .activeChatBan(null)
                .metaData(new HashMap<>())
                .build();

        assertFalse(profile.metaData().isEmpty());
        profile.addMetaData("test", "test");
        assertTrue(profile.hasMetaData("test"));
        assertEquals("test", profile.getMetaData("test").get());

        profile.removeMetaData("test");
        assertFalse(profile.hasMetaData("test"));
    }

    @Test
    void testPunishUpdate() {
        PunishProfile profile = PunishProfile.builder()
                .owner("TestOwner")
                .activeBan(null)
                .activeChatBan(null)
                .metaData(new HashMap<>())
                .build();

        assertFalse(profile.activeBan().isPresent());
        assertFalse(profile.activeChatBan().isPresent());
        assertEquals(0, profile.history().size());

        UUID source = UUID.randomUUID();

        PunishEntry entry = PunishEntry.builder()
                .type(PunishType.NETWORK)
                .source(source)
                .build();

        PunishProfile updatedProfile = PunishProfile.builder(profile)
                .activeBan(entry)
                .build();

        assertTrue(updatedProfile.activeBan().isPresent());
    }
}
