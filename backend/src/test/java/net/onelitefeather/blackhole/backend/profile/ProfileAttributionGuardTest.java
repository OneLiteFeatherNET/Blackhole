package net.onelitefeather.blackhole.backend.profile;

import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileRequestDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileAttributionGuardTest {

    private static final String OWNER = "ab".repeat(64);
    private static final UUID STAFF = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_STAFF = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID FORGED = UUID.fromString("deadbeef-0000-4000-8000-000000000000");

    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> store =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier).build();
    private final ProfileAttributionGuard guard = new ProfileAttributionGuard(this.store.repository());

    private void givenStored(String id, UUID source, UUID recordedBy) {
        PunishmentEntity stored = new PunishmentEntity(id, source, OWNER, "chat", false, 1L, null, new HashMap<>());
        stored.setRecordedByOid(recordedBy);
        this.store.given(stored, id);
    }

    private static PunishEntryDTO entry(String id, UUID source, UUID recordedBy) {
        return new PunishEntryDTO(id, "#AAAAAAAA", source, recordedBy, recordedBy != null, null, null, "chat", null, false, false, 1L,
                null, null, null, null, null, null, null, null, null, new HashMap<>());
    }

    private PunishEntryDTO sanitizedActive(PunishEntryDTO entry) {
        return this.guard.sanitize(new PunishProfileRequestDTO(OWNER, Map.of("chat", entry), List.of(), Map.of())).active().get("chat");
    }

    @Test
    void aNewEntryIsUnverifiedWhateverTheClientSends() {
        PunishEntryDTO result = sanitizedActive(entry("new-1", STAFF, FORGED));

        assertNull(result.recordedByOid(), "a client can never set recordedByOid");
        assertFalse(result.verified(), "so a new entry is unverified");
    }

    @Test
    void anExistingEntryKeepsItsStoredRecordedByOidAndIgnoresTheSpoofedOne() {
        givenStored("p-1", STAFF, STAFF);

        PunishEntryDTO result = sanitizedActive(entry("p-1", STAFF, FORGED));

        assertEquals(STAFF, result.recordedByOid(), "the stored value wins");
        assertTrue(result.verified());
    }

    @Test
    void anExistingLegacyEntryStaysUnverifiedEvenIfTheClientClaimsAVerifiedOne() {
        givenStored("p-2", STAFF, null);

        PunishEntryDTO result = sanitizedActive(entry("p-2", STAFF, FORGED));

        assertNull(result.recordedByOid(), "nothing is stored, so nothing is verified");
        assertFalse(result.verified());
    }

    @Test
    void changingTheSourceOfAnExistingEntryDropsItsVerification() {
        givenStored("p-3", STAFF, STAFF);

        PunishEntryDTO result = sanitizedActive(entry("p-3", OTHER_STAFF, STAFF));

        assertEquals(OTHER_STAFF, result.source(), "the existing semantics: the source is written as sent");
        assertNull(result.recordedByOid(), "but the entry is no longer verified");
        assertFalse(result.verified());
    }

    @Test
    void historyEntriesAreSanitizedLikeActiveOnes() {
        givenStored("p-4", STAFF, STAFF);

        PunishProfileRequestDTO result = this.guard.sanitize(new PunishProfileRequestDTO(OWNER, null,
                List.of(entry("p-4", STAFF, FORGED), entry("new-2", STAFF, FORGED)), Map.of()));

        assertEquals(STAFF, result.history().get(0).recordedByOid(), "stored entry keeps its value");
        assertNull(result.history().get(1).recordedByOid(), "new entry has none");
        assertNull(result.active(), "a missing active map stays missing");
    }

    @Test
    void aClientCannotSetTheActorOidOfAnEntry() {
        givenStored("p-5", STAFF, STAFF);
        PunishEntryDTO forged = entry("p-5", STAFF, STAFF).withActorOid(FORGED);

        PunishEntryDTO result = sanitizedActive(forged);

        assertNull(result.actorOid(), "the stored actor oid (none) wins over the forged one");
    }

    @Test
    void anExistingEntryKeepsItsStoredActorOid() {
        givenStored("p-6", STAFF, STAFF);
        this.store.repository().findById("p-6").orElseThrow().setActorOid(OTHER_STAFF);

        PunishEntryDTO result = sanitizedActive(entry("p-6", STAFF, STAFF));

        assertEquals(OTHER_STAFF, result.actorOid(), "profile writes never lose the attribution");
    }
}
