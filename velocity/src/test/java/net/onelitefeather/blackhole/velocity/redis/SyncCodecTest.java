package net.onelitefeather.blackhole.velocity.redis;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire formats the backend writes: the sync message of the per-section keys and channel,
 * and the shared-cache lists the proxy reads for sections and tab completion.
 */
class SyncCodecTest {

    private final SyncCodec codec = new SyncCodec();

    @Test
    void aSetMessageOfTheBackendIsReadWithAllItsFields() throws Exception {
        String json = "{\"owner\":\"o\",\"section\":\"network\",\"enforcement\":\"BAN\",\"state\":\"SET\","
                + "\"punishmentIdentifier\":\"p1\",\"templateIdentifier\":\"t1\",\"reason\":\"<red>Hacking\",\"expiresAt\":1234}";

        PunishmentSyncMessage message = codec.message(json);

        assertEquals("network", message.section());
        assertEquals("BAN", message.enforcement());
        assertEquals("<red>Hacking", message.reason());
        assertEquals(1234L, message.expiresAt());
        assertTrue(message.isSet());
    }

    @Test
    void aPermanentPunishmentHasNoExpiry() throws Exception {
        PunishmentSyncMessage message = codec.message("{\"owner\":\"o\",\"section\":\"network\",\"state\":\"SET\",\"expiresAt\":null}");

        assertNull(message.expiresAt());
    }

    @Test
    void fieldsAddedByALaterBackendAreIgnored() throws Exception {
        PunishmentSyncMessage message = codec.message("{\"owner\":\"o\",\"section\":\"chat\",\"state\":\"CLEARED\",\"somethingNew\":1}");

        assertEquals("chat", message.section());
    }

    @Test
    void textThatIsNoSyncMessageIsEmpty() {
        assertTrue(codec.tryMessage("not json").isEmpty());
        assertTrue(codec.tryMessage(null).isEmpty());
    }

    @Test
    void theCachedSectionListIsReadIntoSectionInfos() {
        String json = "[{\"key\":\"bedwars\",\"displayName\":\"Bedwars\",\"enforcement\":\"SERVER\",\"servers\":[\"bedwars-*\"],"
                + "\"baseScore\":1000,\"permanentThreshold\":300,\"pardonScore\":700,\"permanentReason\":\"r\",\"decayPerDay\":10,\"enabled\":true}]";

        List<SectionInfo> sections = codec.sections(json).orElseThrow();

        assertEquals(1, sections.size());
        assertEquals(EnforcementKind.SERVER, sections.getFirst().enforcement());
        assertEquals(List.of("bedwars-*"), sections.getFirst().servers());
    }

    @Test
    void aBrokenSectionListIsEmptyInsteadOfAFailure() {
        assertTrue(codec.sections("{}").isEmpty());
    }

    @Test
    void theCachedTemplateListIsReadIntoTheClientModel() {
        UUID id = UUID.randomUUID();
        String json = "[{\"identifier\":\"" + id + "\",\"key\":\"hacking\",\"reason\":\"<red>Hacking\",\"section\":\"network\","
                + "\"steps\":[{\"duration\":\"P1D\",\"eloCost\":100}]}]";

        List<PunishTemplateResponse> templates = codec.templates(json).orElseThrow();

        assertEquals("hacking", templates.getFirst().getKey());
        assertEquals("network", templates.getFirst().getSection());
        assertEquals(100, templates.getFirst().getSteps().getFirst().getEloCost());
    }
}
