package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PanelUrlConfigTest {

    private static BlackholeConfig load(Path dir, String json) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return BlackholeConfig.load(file);
    }

    @Test
    void thePanelUrlIsOptionalAndAbsentByDefault() {
        assertNull(new BlackholeConfig().getPanelUrl());
    }

    @Test
    void aConfigWrittenBeforeThePanelUrlExistedHasNone(@TempDir Path dir) throws IOException {
        assertNull(load(dir, "{\"baseUrl\": \"http://localhost:8080\"}").getPanelUrl());
    }

    @Test
    void anHttpsAddressIsUsedWithoutItsTrailingSlash(@TempDir Path dir) throws IOException {
        assertEquals("https://panel.example.org", load(dir, "{\"panelUrl\": \" https://panel.example.org// \"}").getPanelUrl());
    }

    @Test
    void anAddressThatIsNotHttpIsIgnored(@TempDir Path dir) throws IOException {
        assertNull(load(dir, "{\"panelUrl\": \"javascript:alert(1)\"}").getPanelUrl(), "the panel button must never open a script or file link");
    }
}
