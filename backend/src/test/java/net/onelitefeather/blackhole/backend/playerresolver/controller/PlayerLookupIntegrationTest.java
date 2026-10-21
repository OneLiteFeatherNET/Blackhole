package net.onelitefeather.blackhole.backend.playerresolver.controller;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resolves a name through the full HTTP stack with the Mojang resolver pointed at a fake Mojang
 * served by the same embedded server. The declarative Mojang client blocks, so this fails with
 * "block() ... not supported in thread default-eventLoopGroup" when the request is handled on the
 * Netty event loop.
 */
@Tag("integration")
class PlayerLookupIntegrationTest extends ApiIntegrationTest {

    private final int port = freePort();

    @Override
    protected Map<String, Object> extraProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("test.fake-mojang", "true");
        properties.put("micronaut.server.port", this.port);
        properties.put("micronaut.http.services.mojang.url", "http://localhost:" + this.port);
        return properties;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port", e);
        }
    }

    @Test
    void aNameIsResolvedThroughMojangWithoutBlockingTheEventLoop() {
        Reply reply = get("/player/resolve/Notch");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", String.valueOf(reply.json().get("uuid")), reply.body());
    }
}
