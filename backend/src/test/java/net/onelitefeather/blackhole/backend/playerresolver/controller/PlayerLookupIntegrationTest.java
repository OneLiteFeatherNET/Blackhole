package net.onelitefeather.blackhole.backend.playerresolver.controller;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resolves a name through the full HTTP stack with the Mojang resolver pointed at a fake Mojang
 * on a loopback port, so no real network is used. The declarative Mojang client blocks, so this
 * fails with "block() ... not supported in thread default-eventLoopGroup" when the request is
 * handled on the Netty event loop.
 */
@Tag("integration")
class PlayerLookupIntegrationTest extends ApiIntegrationTest {

    /** Created per test instance, before the application context starts, so its URL is known. */
    private final FakeMojang mojang = startFakeMojang();

    private static FakeMojang startFakeMojang() {
        try {
            return new FakeMojang();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the fake Mojang", e);
        }
    }

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.player-resolver.mojang.base-url", this.mojang.baseUrl());
    }

    @AfterEach
    void stopFakeMojang() {
        this.mojang.close();
    }

    @Test
    void aNameIsResolvedThroughMojangWithoutBlockingTheEventLoop() {
        Reply reply = get("/player/resolve/" + FakeMojang.KNOWN_NAME);

        assertEquals(200, reply.status(), reply.body());
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", String.valueOf(reply.json().get("uuid")), reply.body());
        assertEquals(List.of(FakeMojang.KNOWN_NAME), this.mojang.requestedNames(),
                "the lookup must be answered by the fake Mojang");
    }
}
