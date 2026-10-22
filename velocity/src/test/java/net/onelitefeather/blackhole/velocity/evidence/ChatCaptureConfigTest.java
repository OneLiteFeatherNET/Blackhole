package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCaptureConfigTest {

    @TempDir
    Path directory;

    private ChatCaptureConfig loaded(String json) throws IOException {
        Path file = directory.resolve("config.json");
        Files.writeString(file, json);
        return BlackholeConfig.load(file).getEvidence().getChatCapture();
    }

    @Test
    void aFreshConfigLeavesCaptureOff() {
        assertFalse(new BlackholeConfig().getEvidence().getChatCapture().isEnabled(), "capture must be opt-in");
    }

    @Test
    void aConfigFileWithoutAnEvidenceSectionLeavesCaptureOff() throws IOException {
        ChatCaptureConfig config = loaded("{\"baseUrl\": \"http://localhost:8080\"}");

        assertFalse(config.isEnabled(), "configs written before the setting existed must not start buffering chat");
        assertEquals(20, config.getLines(), "missing settings use their defaults");
    }

    @Test
    void aSavedDefaultConfigStillLeavesCaptureOff() {
        Path file = directory.resolve("saved.json");
        BlackholeConfig.save(new BlackholeConfig(), file);

        assertFalse(BlackholeConfig.load(file).getEvidence().getChatCapture().isEnabled(), "the written default must keep capture off");
    }

    @Test
    void theDocumentedDefaultsApply() {
        ChatCaptureConfig config = new ChatCaptureConfig();

        assertEquals(20, config.getLines());
        assertEquals(5, config.getContextLines());
        assertEquals(Duration.ofMinutes(10), config.getMaxAge());
        assertEquals("velocity", config.getSourceSystem());
        assertEquals(Duration.ofSeconds(2), config.getTimeout());
    }

    @Test
    void capturedValuesAreReadFromTheFile() throws IOException {
        ChatCaptureConfig config = loaded("""
                {"evidence": {"chatCapture": {"enabled": true, "lines": 30, "contextLines": 0,
                 "maxAge": "PT5M", "sourceSystem": "proxy-eu", "timeout": "PT3S"}}}""");

        assertTrue(config.isEnabled());
        assertEquals(30, config.getLines());
        assertEquals(0, config.getContextLines(), "0 switches the third-party context off");
        assertEquals(Duration.ofMinutes(5), config.getMaxAge());
        assertEquals("proxy-eu", config.getSourceSystem());
        assertEquals(Duration.ofSeconds(3), config.getTimeout());
    }

    @Test
    void outOfRangeLineCountsFallBackToTheDefaults() throws IOException {
        ChatCaptureConfig config = loaded("{\"evidence\": {\"chatCapture\": {\"lines\": 500, \"contextLines\": -1}}}");

        assertEquals(20, config.getLines(), "more lines than the backend accepts must not be used");
        assertEquals(5, config.getContextLines());
    }

    @Test
    void unparsableOrNonPositiveDurationsFallBackToTheDefaults() throws IOException {
        ChatCaptureConfig config = loaded("{\"evidence\": {\"chatCapture\": {\"maxAge\": \"ten minutes\", \"timeout\": \"PT0S\"}}}");

        assertEquals(Duration.ofMinutes(10), config.getMaxAge());
        assertEquals(Duration.ofSeconds(2), config.getTimeout());
    }

    @Test
    void aBlankOrOversizedSourceSystemFallsBackToVelocity() throws IOException {
        assertEquals("velocity", loaded("{\"evidence\": {\"chatCapture\": {\"sourceSystem\": \" \"}}}").getSourceSystem());
        assertEquals("velocity", loaded("{\"evidence\": {\"chatCapture\": {\"sourceSystem\": \"" + "x".repeat(65) + "\"}}}").getSourceSystem());
    }

    @Test
    void anExplicitNullSectionLeavesCaptureOff() throws IOException {
        assertFalse(loaded("{\"evidence\": {\"chatCapture\": null}}").isEnabled(), "a null section must not break the plugin");
        assertFalse(loaded("{\"evidence\": null}").isEnabled(), "a null evidence section must not break the plugin");
    }
}
