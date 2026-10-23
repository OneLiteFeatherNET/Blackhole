package net.onelitefeather.blackhole.backend.banid;

import net.onelitefeather.blackhole.backend.banid.dto.BanIdDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanIdServiceTest {

    /** Hands out the scripted candidates in order. */
    private static final class ScriptedGenerator extends BanIdGenerator {
        private final Queue<String> script;

        ScriptedGenerator(String... candidates) {
            this.script = new ArrayDeque<>(java.util.List.of(candidates));
        }

        @Override
        public String generate() {
            return this.script.remove();
        }
    }

    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> this.punishments.all().stream().filter(p -> args[0].equals(p.getShortId())).toList())
                    .build();

    private BanIdTestBed bed(BanIdGenerator generator) {
        return new BanIdTestBed(this.punishments.repository(), generator);
    }

    private void givenPunishment(BanIdTestBed bed, String identifier) {
        this.punishments.given(new PunishmentEntity(identifier, UUID.randomUUID(), "owner", "network", false, 1L, null, new HashMap<>()), identifier);
        bed.store.givenPunishment(identifier);
    }

    @Test
    void issuingGivesThePunishmentOneBanIdThatDecryptsToTheCandidate() {
        BanIdTestBed bed = bed(new ScriptedGenerator("K7M29XYZ"));
        givenPunishment(bed, "p1");

        assertTrue(bed.service.issue("p1"));

        assertEquals("K7M29XYZ", bed.crypto.decrypt(bed.store.encrypted().get("p1"), "p1"));
        assertEquals(Optional.of("p1"), bed.service.findIdentifier("#k7m2 9xyz"), "found by what a player types");
    }

    @Test
    void aCollisionIsRetriedWithANewCandidate() {
        BanIdTestBed bed = bed(new ScriptedGenerator("K7M29XYZ", "K7M29XYZ", "ABCD2345"));
        givenPunishment(bed, "p1");
        givenPunishment(bed, "p2");
        bed.service.issue("p1");

        assertTrue(bed.service.issue("p2"));

        assertEquals("ABCD2345", bed.crypto.decrypt(bed.store.encrypted().get("p2"), "p2"));
    }

    @Test
    void aCandidateEqualToALegacyShortIdIsDiscarded() {
        BanIdTestBed bed = bed(new ScriptedGenerator("A1B2C3D4", "ABCD2345"));
        bed.store.givenLegacyShortId("A1B2C3D4");
        givenPunishment(bed, "p1");

        assertTrue(bed.service.issue("p1"));

        assertEquals("ABCD2345", bed.crypto.decrypt(bed.store.encrypted().get("p1"), "p1"));
    }

    @Test
    void issuingTwiceKeepsTheFirstBanId() {
        BanIdTestBed bed = bed(new ScriptedGenerator("K7M29XYZ", "ABCD2345"));
        givenPunishment(bed, "p1");
        bed.service.issue("p1");

        assertFalse(bed.service.issue("p1"), "nothing issued the second time");

        assertEquals("K7M29XYZ", bed.crypto.decrypt(bed.store.encrypted().get("p1"), "p1"));
    }

    @Test
    void manyPunishmentsGetDistinctBanIdsInTheAlphabet() {
        BanIdTestBed bed = bed(new BanIdGenerator(new Random(7)));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String identifier = "p" + i;
            givenPunishment(bed, identifier);
            assertTrue(bed.service.issue(identifier), identifier);
            String banId = bed.crypto.decrypt(bed.store.encrypted().get(identifier), identifier);
            assertTrue(BanIdCodec.normalize(banId).isPresent(), "a valid Ban ID: " + banId.length());
            assertTrue(seen.add(banId), "unique");
        }
    }

    @Test
    void aLegacyShortIdIsNoBanId() {
        BanIdTestBed bed = bed(new ScriptedGenerator("K7M29XYZ"));
        givenPunishment(bed, "p1");
        bed.service.issue("p1");

        assertEquals(Optional.empty(), bed.service.findIdentifier("#A1B2C3D4"));
        assertEquals(Optional.empty(), bed.service.findIdentifier("not a ban id"));
    }

    @Test
    void revealReturnsTheBanIdOfAKnownPunishment() {
        BanIdTestBed bed = bed(new ScriptedGenerator("K7M29XYZ"));
        givenPunishment(bed, "p1");
        bed.service.issue("p1");

        BanIdDTO result = bed.service.reveal("p1");

        assertEquals("K7M29XYZ", assertInstanceOf(BanIdDTO.Response.class, result).banId());
    }

    @Test
    void revealOfAPunishmentWithoutABanIdOrOfAnUnknownOneIsNotFound() {
        BanIdTestBed bed = bed(new ScriptedGenerator());
        givenPunishment(bed, "p1");

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(BanIdDTO.Error.class, bed.service.reveal("p1")).kind(), "not issued yet");
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(BanIdDTO.Error.class, bed.service.reveal("nobody")).kind(), "unknown");
    }

    @Test
    void revealWithACiphertextOfAnotherKeyIsUnavailable() {
        BanIdTestBed bed = bed(new ScriptedGenerator());
        givenPunishment(bed, "p1");
        bed.store.assign("p1", new byte[32], new byte[40]);

        assertEquals(ErrorKind.UNAVAILABLE, assertInstanceOf(BanIdDTO.Error.class, bed.service.reveal("p1")).kind());
    }

    @Test
    void revealByAnAmbiguousLegacyShortIdIsAConflict() {
        BanIdTestBed bed = bed(new ScriptedGenerator());
        PunishmentEntity first = new PunishmentEntity("a1b2c3d4-0000-4000-8000-000000000001", UUID.randomUUID(), "o", "network", false, 1L, null, new HashMap<>());
        PunishmentEntity second = new PunishmentEntity("a1b2c3d4-0000-4000-8000-000000000002", UUID.randomUUID(), "o", "network", false, 1L, null, new HashMap<>());
        this.punishments.given(first, first.getIdentifier());
        this.punishments.given(second, second.getIdentifier());

        assertEquals(ErrorKind.CONFLICT, assertInstanceOf(BanIdDTO.Error.class, bed.service.reveal("#A1B2C3D4")).kind());
    }

    @Test
    void textWithABanIdShapeThatIsNoLegacyShortIdIsDetected() {
        BanIdTestBed bed = bed(new ScriptedGenerator());

        assertTrue(bed.service.containsBanId("he said #K7M29XYZ"), "not hex, so a Ban ID shape");
        assertTrue(bed.service.containsBanId("he said # k7m29xyz"), "typed loosely");
        assertFalse(bed.service.containsBanId("see #A1B2C3D4"), "hex only and issued to nobody: a legacy short id");
        assertFalse(bed.service.containsBanId("K7M29XYZ without hash sign"), "no # no shape");
    }

    @Test
    void aHexOnlyBanIdIsDetectedBecauseItIsIssued() {
        BanIdTestBed bed = bed(new ScriptedGenerator("A1B2C3D4"));
        givenPunishment(bed, "p1");
        bed.service.issue("p1");

        assertTrue(bed.service.containsBanId("see #A1B2C3D4"));
    }
}
