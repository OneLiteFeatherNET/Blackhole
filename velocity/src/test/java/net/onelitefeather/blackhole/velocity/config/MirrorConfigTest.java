package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorConfigTest {

    @Test
    void aConfigWithoutTheSettingCapsTheMirrorAtTheDefault() {
        assertEquals(4096, new MirrorConfig().getMaxOwners());
    }

    @Test
    void aConfiguredCapIsUsed() {
        MirrorConfig config = new MirrorConfig();
        config.setMaxOwners(10);

        assertEquals(10, config.getMaxOwners());
    }

    @Test
    void aCapBelowOneFallsBackToTheDefault() {
        MirrorConfig config = new MirrorConfig();
        config.setMaxOwners(0);

        assertEquals(4096, config.getMaxOwners(), "a cap of zero would hold nobody, so the default applies");
    }
}
