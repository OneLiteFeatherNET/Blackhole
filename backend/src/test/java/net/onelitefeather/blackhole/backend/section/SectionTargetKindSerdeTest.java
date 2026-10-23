package net.onelitefeather.blackhole.backend.section;

import io.micronaut.serde.ObjectMapper;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON the proxies read from the shared cache: additive, and tolerant of entries written before target kinds. */
class SectionTargetKindSerdeTest {

    private static final String OLD_ENTRY = """
            {"key":"bedwars","displayName":"Bedwars","enforcement":"SERVER","servers":["bedwars-*"],
             "baseScore":1000,"softThreshold":700,"softDuration":"P3D","permanentThreshold":300,
             "pardonScore":700,"permanentReason":"banned","decayPerDay":10,"enabled":true}""";

    @Test
    void aCacheEntryWithoutATargetKindReadsAsPattern() throws IOException {
        SectionDTO.Response section = ObjectMapper.getDefault().readValue(OLD_ENTRY, SectionDTO.Response.class);

        assertEquals(ServerTargetKind.PATTERN, section.targetKind(), "an entry cached before this release");
        assertEquals(List.of("bedwars-*"), section.servers(), "the rest of the entry is read as before");
    }

    @Test
    void aResponseIsWrittenWithItsTargetKind() throws IOException {
        SectionDTO.Response section = new SectionDTO.Response("bedwars", "Bedwars", EnforcementKind.SERVER,
                ServerTargetKind.CLOUDNET_GROUP, List.of("Minigames"), 1000, 700, "P3D", 300, 700, "banned", 10, true);

        String json = ObjectMapper.getDefault().writeValueAsString(section);

        assertTrue(json.contains("\"targetKind\":\"CLOUDNET_GROUP\""), "targetKind is part of the payload: " + json);
        assertEquals(section, ObjectMapper.getDefault().readValue(json, SectionDTO.Response.class), "round trip");
    }
}
