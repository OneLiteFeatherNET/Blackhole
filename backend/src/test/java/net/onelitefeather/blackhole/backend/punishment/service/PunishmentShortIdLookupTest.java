package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentShortIdLookupTest {

    private static final UUID SHARED = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentEntity stored(UUID uuid) {
        String id = IdGenerator.generateId(uuid);
        PunishmentEntity entity = new PunishmentEntity(id, STAFF, OWNER, "chat", false, 1L, null, new HashMap<>());
        bed.punishments.given(entity, id);
        return entity;
    }

    private PunishmentDTO.ShortIdMatches find(String input) {
        return assertInstanceOf(PunishmentDTO.ShortIdMatches.class, bed.punishmentService.findByShortId(input));
    }

    @Test
    void aNewPunishmentCarriesTheShortIdOfItsIdentifier() {
        PunishmentEntity entity = stored(SHARED);

        assertEquals("A1B2C3D4", entity.getShortId());
        assertEquals("#A1B2C3D4", entity.toDTO().shortId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"#a1b2c3d4", "A1B2C3D4", "a1b2c3d4", " #A1B2C3D4 "})
    void theLookupFindsAPunishmentHoweverTheShortIdWasTyped(String input) {
        stored(SHARED);

        assertEquals(1, find(input).punishments().size());
    }

    @Test
    void theLookupReturnsEveryPunishmentWhenShortIdsCollide() {
        stored(SHARED);
        stored(UUID.fromString("a1b2c3d4-ffff-4000-8000-000000000002"));
        stored(UUID.fromString("a1b2c3d5-0000-4000-8000-000000000003"));

        var found = find("#A1B2C3D4").punishments();

        assertEquals(2, found.size());
        assertTrue(found.stream().map(PunishEntryDTO::shortId).allMatch("#A1B2C3D4"::equals));
    }

    @Test
    void theLookupIsEmptyWhenNothingMatches() {
        stored(SHARED);

        assertTrue(find("#00000000").punishments().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "#", "A1B2C3D", "A1B2C3D45", "XYZ12345", "A1B2C3D4 extra"})
    void aMalformedShortIdIsAValidationError(String input) {
        PunishmentDTO.Error error = assertInstanceOf(PunishmentDTO.Error.class, bed.punishmentService.findByShortId(input));

        assertEquals(ErrorKind.VALIDATION, error.kind());
    }

    @Test
    void anAppliedPunishmentCanBeFoundByTheShortIdOfItsIdentifier() {
        bed.givenTemplate("spam", "chat", null, PunishmentTestBed.step("PT1H", 50));
        var applied = assertInstanceOf(PunishmentDTO.ApplyResponse.class,
                bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, STAFF)));

        String shortId = applied.punishment().shortId();

        assertEquals(PunishmentShortId.display(PunishmentShortId.keyOf(applied.punishment().identifier()).orElseThrow()), shortId);
        assertEquals(applied.punishment().identifier(), find(shortId.toLowerCase()).punishments().getFirst().identifier());
    }
}
