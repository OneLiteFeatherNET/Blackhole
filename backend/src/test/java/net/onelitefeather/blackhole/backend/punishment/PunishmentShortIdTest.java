package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first vectors are shared with the Velocity plugin's {@code PunishmentScreensTest}. */
class PunishmentShortIdTest {

    private static final UUID UUID_ID = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");
    private static final String BASE64_ID = IdGenerator.generateId(UUID_ID);

    @Test
    void theBackendIdentifierOfTheSharedVectorYieldsTheSharedShortId() {
        assertEquals(Optional.of("A1B2C3D4"), PunishmentShortId.keyOf(BASE64_ID));
        assertEquals("#A1B2C3D4", PunishmentShortId.display("A1B2C3D4"));
    }

    @Test
    void aPlainUuidYieldsTheSameShortId() {
        assertEquals(Optional.of("A1B2C3D4"), PunishmentShortId.keyOf(UUID_ID.toString()));
        assertEquals(Optional.of("A1B2C3D4"), PunishmentShortId.keyOf(UUID_ID.toString().toUpperCase()));
    }

    @ParameterizedTest
    @CsvSource({
            "00000000-0000-0000-0000-000000000000, 00000000",
            "ffffffff-ffff-4fff-bfff-ffffffffffff, FFFFFFFF",
            "0123abcd-4567-4890-8abc-def012345678, 0123ABCD"
    })
    void theBase64AndPlainFormsOfTheSameUuidAgree(String uuid, String expected) {
        assertEquals(Optional.of(expected), PunishmentShortId.keyOf(uuid));
        assertEquals(Optional.of(expected), PunishmentShortId.keyOf(IdGenerator.generateId(UUID.fromString(uuid))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc", "!!!!!!!!!!!!!!!!!!!!!!", "not-a-uuid-not-a-uuid-not-a-uuid-xx"})
    void anUnknownIdentifierFormatHasNoShortId(String identifier) {
        assertEquals(Optional.empty(), PunishmentShortId.keyOf(identifier));
    }

    @Test
    void theStorageKeyOfAnUnknownIdentifierIsDeterministic() {
        String key = PunishmentShortId.storageKey("legacy-id");
        assertTrue(key.matches("[0-9A-F]{8}"), key);
        assertEquals(key, PunishmentShortId.storageKey("legacy-id"));
    }

    @Test
    void theStorageKeyOfARealIdentifierIsItsShortId() {
        assertEquals("A1B2C3D4", PunishmentShortId.storageKey(BASE64_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"#a1b2c3d4", "A1B2C3D4", "a1b2c3d4", "#A1B2C3D4", "  #a1B2c3D4 \n", "\tA1B2C3D4"})
    void parseNormalisesWhatAUserTyped(String input) {
        assertEquals(Optional.of("A1B2C3D4"), PunishmentShortId.parse(input));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "#", "##A1B2C3D4", "A1B2C3D", "A1B2C3D45", "G1B2C3D4", "A1B2 C3D4", "#A1B2C3D4#", "A1B2C3D4-"})
    void parseRejectsAnythingThatIsNotExactlyEightHexCharacters(String input) {
        assertEquals(Optional.empty(), PunishmentShortId.parse(input));
    }
}
