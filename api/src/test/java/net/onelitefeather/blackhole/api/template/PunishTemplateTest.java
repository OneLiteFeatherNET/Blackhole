package net.onelitefeather.blackhole.api.template;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.punish.PunishType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PunishTemplateTest {

    @Test
    void testObjectCreation() {
        PunishTemplate entry = PunishTemplate.builder()
                .type(PunishType.SERVER)
                .reason("Test")
                .generateIdentifier()
                .build();
        assertNotNull(entry);
        assertEquals(PunishType.SERVER, entry.type());
        assertEquals("Test", entry.reason());
        assertNotNull(entry.identifier());
        assertNotNull(entry.metaData().get(Metadata.META_DATA_KEY_CREATION_DATE));
    }

    @Test
    void testObjectCreationWithTranslation() {
        PunishTemplate entry = PunishTemplate.builder()
                .type(PunishType.SERVER)
                .reason("Test")
                .generateIdentifier()
                .translatable()
                .build();
        assertNotNull(entry);
        assertEquals(PunishType.SERVER, entry.type());
        assertEquals("Test", entry.reason());
        assertNotNull(entry.identifier());
        assertNotNull(entry.metaData().get(Metadata.META_DATA_KEY_CREATION_DATE));
        assertNotNull(entry.metaData().get(PunishTemplate.META_DATA_KEY_TRANSLATABLE));
    }

    @Test
    void testObjectManipulation() {
        PunishTemplate entry = PunishTemplate.builder()
                .type(PunishType.SERVER)
                .reason("Test")
                .generateIdentifier()
                .build();
        assertNotNull(entry);

        PunishTemplate updated = PunishTemplate.builder(entry)
                .type(PunishType.CHAT)
                .build();

        assertNotNull(updated);
        assertEquals(PunishType.CHAT, updated.type());
        assertNotEquals(entry, updated);
        assertNotEquals(entry.type(), updated.type());
        assertEquals("Test", updated.reason());
    }

}
