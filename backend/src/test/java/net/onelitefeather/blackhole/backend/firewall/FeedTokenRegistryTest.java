package net.onelitefeather.blackhole.backend.firewall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedTokenRegistryTest {

    private static final Set<String> PRODUCTION = Set.of("prod");
    private static final Set<String> TEST = Set.of("test");
    private static final String TOKEN = "feed-token-under-test";
    private static final String NEXT = "bmV4dG5leHRuZXh0bmV4dG5leHRuZXh0bmV4dG5leHRuZXh0";

    @TempDir
    Path secrets;

    private void writeHash(String file, String token) throws IOException {
        Files.writeString(this.secrets.resolve(file), HexFormat.of().formatHex(FeedTokenRegistry.sha256(token)) + "\n");
    }

    @Test
    void aTokenMatchesItsClientByHash() throws IOException {
        writeHash("edge", TOKEN);
        FeedTokenRegistry registry = new FeedTokenRegistry("edge", this.secrets, PRODUCTION);

        assertEquals("edge", registry.clientOf(TOKEN).orElseThrow(), "the token of the hash file is accepted");
    }

    @Test
    void anUnknownTokenMatchesNothing() throws IOException {
        writeHash("edge", TOKEN);
        FeedTokenRegistry registry = new FeedTokenRegistry("edge", this.secrets, PRODUCTION);

        assertFalse(registry.clientOf("not-the-token").isPresent(), "an unknown token is refused");
    }

    @Test
    void bothTokensAreAcceptedDuringARotation() throws IOException {
        writeHash("edge", TOKEN);
        writeHash("edge.next", NEXT);
        FeedTokenRegistry registry = new FeedTokenRegistry("edge", this.secrets, PRODUCTION);

        assertTrue(registry.clientOf(TOKEN).isPresent(), "the current token still works");
        assertTrue(registry.clientOf(NEXT).isPresent(), "the next token works before the old file is removed");
    }

    @Test
    void aMissingHashFileStopsTheStartOutsideLocalEnvironments() {
        assertThrows(IllegalStateException.class, () -> new FeedTokenRegistry("edge", this.secrets, PRODUCTION),
                "a client without a hash file is a start-up error in production");
    }

    @Test
    void aMalformedHashFileStopsTheStartOutsideLocalEnvironments() throws IOException {
        Files.writeString(this.secrets.resolve("edge"), "not hex");

        assertThrows(IllegalStateException.class, () -> new FeedTokenRegistry("edge", this.secrets, PRODUCTION),
                "a hash that is not 64 lowercase hex characters stops the start");
    }

    @Test
    void aMissingHashFileIsSkippedInTheTestEnvironment() {
        FeedTokenRegistry registry = new FeedTokenRegistry("edge", this.secrets, TEST);

        assertFalse(registry.clientOf(TOKEN).isPresent(), "the client is skipped with a warning, not a failure");
    }
}
