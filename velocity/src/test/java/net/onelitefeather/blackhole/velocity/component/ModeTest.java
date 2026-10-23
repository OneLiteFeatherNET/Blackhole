package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.audience.Audience;
import net.onelitefeather.blackhole.velocity.evidence.FakePlayer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModeTest {

    @Test
    void aPlayerGetsRichMessages() {
        assertEquals(Mode.RICH, Mode.of(FakePlayer.offServer(UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001"))));
    }

    @Test
    void anyOtherReceiverGetsPlainMessages() {
        assertEquals(Mode.PLAIN, Mode.of(Audience.empty()), "the console is not a player");
    }
}
