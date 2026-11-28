package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.TestData;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A login reads the profile with the login reader; the chat and connect checks read it with the chat reader. */
class PunishmentStateLoginReadTest {

    private static final String OWNER = "b".repeat(128);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);

    private static PunishmentState.ProfileReader recording(List<String> reads) {
        return new PunishmentState.ProfileReader() {
            @Override
            public Optional<PunishProfileDTO> read(String owner) {
                reads.add("chat");
                return Optional.empty();
            }

            @Override
            public Optional<PunishProfileDTO> readAtLogin(String owner) {
                reads.add("login");
                return Optional.empty();
            }
        };
    }

    @Test
    void aLoginReadsThroughTheLoginReader() {
        List<String> reads = new ArrayList<>();
        PunishmentState state = new PunishmentState(TestData.standardRegistry(), new PunishmentMirror(CLOCK), recording(reads),
                CLOCK, () -> false, Duration.ofMinutes(1));

        state.onLogin(OWNER);

        assertEquals(List.of("login"), reads, "a login is decided on the read that has the login timeout");
    }

    @Test
    void aMuteCheckReadsThroughTheChatReader() {
        List<String> reads = new ArrayList<>();
        PunishmentState state = new PunishmentState(TestData.standardRegistry(), new PunishmentMirror(CLOCK), recording(reads),
                CLOCK, () -> false, Duration.ofMinutes(1));

        state.activeMute(OWNER);

        assertEquals(List.of("chat"), reads, "a chat message is checked with the shorter chat timeout");
    }
}
