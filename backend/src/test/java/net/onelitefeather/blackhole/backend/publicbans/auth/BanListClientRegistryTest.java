package net.onelitefeather.blackhole.backend.publicbans.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanListClientRegistryTest {

    private static final String PHC = Argon2ClientHashVerifier.encode("correct horse battery", new byte[16]);

    @TempDir
    Path secrets;

    private final ListAppender<ILoggingEvent> warnings = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureWarnings() {
        this.logger = (Logger) LoggerFactory.getLogger(BanListClientRegistry.class);
        this.warnings.start();
        this.logger.addAppender(this.warnings);
    }

    @AfterEach
    void releaseWarnings() {
        this.logger.detachAppender(this.warnings);
    }

    @Test
    void aClientNameOutsideTheAllowedPatternIsRefused() throws IOException {
        write("Website", PHC);

        assertThrows(IllegalStateException.class, () -> new BanListClientRegistry("Website", this.secrets, Set.of("prod")),
                "an upper-case client name is refused at startup");
    }

    @Test
    void aClientWithAWellFormedHashFileIsRegistered() throws IOException {
        write("website", PHC);

        BanListClientRegistry registry = new BanListClientRegistry("website", this.secrets, Set.of("prod"));

        assertTrue(registry.verifiers().get("website").matches("correct horse battery"),
                "the registered client accepts its own password");
    }

    @Test
    void aClientWithoutAHashFileIsAStartupErrorOutsideDevAndTest() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new BanListClientRegistry("website", this.secrets, Set.of("prod")),
                "a configured client with no hash file stops the start outside dev and test");

        assertTrue(failure.getMessage().contains("website"), "the message names the client");
    }

    @Test
    void aClientWithoutAHashFileIsSkippedWithAWarningInDev() {
        BanListClientRegistry registry = new BanListClientRegistry("website", this.secrets, Set.of("dev"));

        assertFalse(registry.verifiers().containsKey("website"), "the client is not registered without its hash file");
        assertTrue(this.warnings.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("website")),
                "a warning names the client that was skipped");
    }

    @Test
    void aMalformedHashIsRefusedAndTheMessageDoesNotContainTheHash() throws IOException {
        write("website", "$argon2i$v=19$m=4096,t=3,p=1$c2FsdHNhbHRzYWx0$secret-looking-text");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new BanListClientRegistry("website", this.secrets, Set.of("prod")),
                "a hash with another algorithm is refused at load time");

        assertFalse(failure.getMessage().contains("secret-looking-text"), "the hash never appears in an error message");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(this.secrets.resolve(name), content, StandardCharsets.UTF_8);
    }
}
