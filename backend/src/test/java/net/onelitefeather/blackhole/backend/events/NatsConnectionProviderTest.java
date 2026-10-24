package net.onelitefeather.blackhole.backend.events;

import io.nats.client.Options;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The connection must never make a caller wait, whatever state the server is in. */
class NatsConnectionProviderTest {

    /** Nothing listens on port 1: the connect is refused at once, every time. */
    private static NatsSettings unreachable(String creds) {
        return new NatsSettings("nats://127.0.0.1:1", creds, "test", Duration.ofSeconds(1), 1, Duration.ofDays(7), 1024);
    }

    @Test
    void askingForTheConnectionWhileTheServerIsDownFailsAtOnceInsteadOfWaiting() {
        NatsConnectionProvider provider = new NatsConnectionProvider(unreachable(""));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(IllegalStateException.class, provider::connection));
        } finally {
            provider.close();
        }
    }

    @Test
    void closingWhileStillTryingToConnectDoesNotHang() {
        NatsConnectionProvider provider = new NatsConnectionProvider(unreachable(""));
        assertThrows(IllegalStateException.class, provider::connection);

        assertTimeoutPreemptively(Duration.ofSeconds(5), provider::close);
    }

    @Test
    void waitingForTheConnectionIsBoundedByTheCallersTimeout() {
        NatsConnectionProvider provider = new NatsConnectionProvider(unreachable(""));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(IllegalStateException.class, () -> provider.await(Duration.ofMillis(200))));
        } finally {
            provider.close();
        }
    }

    @Test
    void theOptionsReconnectForeverAndNameTheClient() {
        Options options = NatsConnectionProvider.options(unreachable(""));

        assertAll(
                () -> assertEquals(-1, options.getMaxReconnect()),
                () -> assertEquals("test", options.getConnectionName()),
                () -> assertEquals(Duration.ofSeconds(1), options.getConnectionTimeout()));
    }

    @Test
    void aMissingCredentialsFileIsLoggedAndTheOptionsStillBuild(@org.junit.jupiter.api.io.TempDir Path directory) {
        Path missing = directory.resolve("nope.creds");

        Options options = assertDoesNotThrow(() -> NatsConnectionProvider.options(unreachable(missing.toString())));

        assertTrue(options.getAuthHandler() == null, "no credentials were wired in");
    }

    @Test
    void aReadableCredentialsFileIsWiredIn(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path creds = Files.writeString(directory.resolve("a.creds"), "unused");

        Options options = NatsConnectionProvider.options(unreachable(creds.toString()));

        assertTrue(options.getAuthHandler() != null, "the file is used for authentication");
    }
}
