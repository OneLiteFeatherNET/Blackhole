package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /guard/settings routes against the real application: viewers read, administrators replace, nobody else does. */
@Tag("integration")
class GuardSettingsControllerTest extends ApiIntegrationTest {

    private static Map<String, Object> change(String level, int minutes) {
        return Map.of("unattendedLevel", level, "absenceThresholdMinutes", minutes, "nightWindowEnabled", true,
                "nightWindowStart", "01:00", "nightWindowEnd", "07:00", "timeZone", "Europe/Berlin");
    }

    @Test
    void aViewerReadsTheSettingsWithTheNextNightWindow() {
        Reply reply = get("/guard/settings", jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertTrue(reply.json().containsKey("nextNightWindowStart"), "the read must show the next night window: " + reply.body());
    }

    @Test
    void aModeratorCannotChangeTheSettings() {
        Reply reply = put("/guard/settings", change("STRICT", 30), jwt.moderator());

        assertEquals(403, reply.status(), "only an administrator may replace the settings: " + reply.body());
    }

    @Test
    void anAdministratorSavesAValidChangeAndTheNextReadShowsIt() {
        Reply saved = put("/guard/settings", change("STRICT", 30), jwt.admin());
        Reply read = get("/guard/settings", jwt.viewer());

        assertEquals(200, saved.status(), saved.body());
        assertEquals(30, read.json().get("absenceThresholdMinutes"), "the next read must show the saved threshold");
    }

    @Test
    void anAdministratorCannotSetOffAsTheUnattendedLevel() {
        Reply reply = put("/guard/settings", change("OFF", 30), jwt.admin());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("VALIDATION", reply.json().get("kind"), "OFF is refused as a validation error");
    }
}
