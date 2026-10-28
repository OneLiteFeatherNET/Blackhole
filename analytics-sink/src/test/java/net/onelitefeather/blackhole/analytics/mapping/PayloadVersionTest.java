package net.onelitefeather.blackhole.analytics.mapping;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PayloadVersionTest {

    private final EventMapper mapper = new EventMapper();

    @Test
    void payloadVersionOneIsMapped() {
        MappingResult result = mapper.map(EventFixtures.envelope(1, "profile.created", Map.of("owner", EventFixtures.OWNER_HASH)));

        assertInstanceOf(Mapped.class, result, "payload version 1 is the version the backend publishes today");
    }

    @Test
    void unknownPayloadVersionIsDeadLetteredWithItsReason() {
        MappingResult result = mapper.map(EventFixtures.envelope(2, "profile.created", Map.of("owner", EventFixtures.OWNER_HASH)));

        DeadLetter deadLetter = assertInstanceOf(DeadLetter.class, result, "an unknown payload version must not be guessed at");
        assertEquals(DeadLetterReason.UNSUPPORTED_VERSION, deadLetter.reason());
    }
}
