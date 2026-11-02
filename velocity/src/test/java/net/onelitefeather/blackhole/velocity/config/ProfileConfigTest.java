package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The profile read timeouts when config.json does not set them. */
class ProfileConfigTest {

    @Test
    void theLoginProfileReadWaitsTwoSecondsByDefault() {
        assertEquals(Duration.ofSeconds(2), new BlackholeConfig().getProfile().getLoginTimeout(),
                "a login is decided on the profile, so it may wait longer");
    }

    @Test
    void theChatProfileReadWaitsOneSecondByDefault() {
        assertEquals(Duration.ofSeconds(1), new BlackholeConfig().getProfile().getChatTimeout(),
                "a chat message or a server connect waits less for the profile");
    }
}
