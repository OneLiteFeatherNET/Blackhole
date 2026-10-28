package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteConfigTest {

    @Test
    void aConfigWithoutTheSettingBlocksTheDefaultPrivateAndEmoteCommands() {
        assertEquals(List.of("msg", "tell", "w", "whisper", "me", "r", "reply"), new MuteConfig().getCommands());
    }

    @Test
    void anEmptyListBlocksNoCommand() {
        MuteConfig config = new MuteConfig();
        config.setCommands(List.of());

        assertTrue(config.getCommands().isEmpty(), "an explicit empty list turns the command gate off");
    }

    @Test
    void entriesAreNormalisedLikeTheCommandWordsOfAnIncomingLine() {
        MuteConfig config = new MuteConfig();
        config.setCommands(List.of("/MSG", " Tell "));

        assertEquals(List.of("msg", "tell"), config.getCommands());
    }
}
