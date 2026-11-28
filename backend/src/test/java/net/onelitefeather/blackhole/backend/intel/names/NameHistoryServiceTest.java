package net.onelitefeather.blackhole.backend.intel.names;

import net.onelitefeather.blackhole.backend.intel.names.dto.NameHistoryDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryNameHistoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The name history rules over an in-memory store: one fixture per test, no database, no clock. */
class NameHistoryServiceTest {

    static final String OWNER = "a".repeat(128);
    static final String OTHER_OWNER = "b".repeat(128);
    static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    final InMemoryNameHistoryStore store = new InMemoryNameHistoryStore();

    NameHistoryService service() {
        return new NameHistoryService(store, true);
    }

    @Test
    void aFirstLoginCreatesOneEntryWithTheRawUuidAndEqualFirstAndLastSeen() {
        service().record(OWNER, PLAYER, "Steve", 1_000L);

        assertEquals(1, store.all().size(), "one entry for the one name");
        NameRecord entry = store.all().getFirst();
        assertEquals(PLAYER, entry.playerUuid(), "the raw UUID is kept");
        assertEquals(1_000L, entry.firstSeen(), "first seen is the login");
        assertEquals(1_000L, entry.lastSeen(), "last seen is the login");
    }

    @Test
    void aLoginInAnotherCaseUpdatesLastSeenKeepsOneEntryAndShowsTheNewestCasing() {
        NameHistoryService service = service();
        service.record(OWNER, PLAYER, "Steve", 1_000L);

        service.record(OWNER, PLAYER, "sTEVE", 2_000L);

        assertEquals(1, store.all().size(), "the same name in another case is the same entry");
        NameRecord entry = store.all().getFirst();
        assertEquals(1_000L, entry.firstSeen(), "first seen is kept");
        assertEquals(2_000L, entry.lastSeen(), "last seen moves forward");
        assertEquals("sTEVE", entry.name(), "the newest casing is shown");
    }

    @Test
    void aNameChangeKeepsBothNamesWithTheOldOneAtItsFinalLogin() {
        NameHistoryService service = service();
        service.record(OWNER, PLAYER, "Steve", 1_000L);
        service.record(OWNER, PLAYER, "Alex", 2_000L);

        NameHistoryDTO.Response page = assertInstanceOf(NameHistoryDTO.Response.class, service.namesOf(OWNER, null, null));

        assertEquals(List.of("Alex", "Steve"), page.names().stream().map(NameHistoryDTO.NameEntry::name).toList(),
                "both names, the newest first");
        assertEquals(1_000L, page.names().get(1).lastSeen(), "the old name keeps its final login");
    }

    @Test
    void theNamesOfAnOwnerArePagedNewestFirst() {
        NameHistoryService service = service();
        service.record(OWNER, PLAYER, "One", 1_000L);
        service.record(OWNER, PLAYER, "Two", 2_000L);
        service.record(OWNER, PLAYER, "Three", 3_000L);

        NameHistoryDTO.Response first = assertInstanceOf(NameHistoryDTO.Response.class, service.namesOf(OWNER, 0, 2));
        NameHistoryDTO.Response second = assertInstanceOf(NameHistoryDTO.Response.class, service.namesOf(OWNER, 1, 2));

        assertEquals(List.of("Three", "Two"), first.names().stream().map(NameHistoryDTO.NameEntry::name).toList());
        assertTrue(first.hasMore(), "a second page exists");
        assertEquals(List.of("One"), second.names().stream().map(NameHistoryDTO.NameEntry::name).toList());
        assertFalse(second.hasMore(), "the last page has no next page");
    }

    @Test
    void theNamesOfAnUnknownOwnerAreAnEmptyPageWithoutAUuid() {
        NameHistoryDTO.Response page = assertInstanceOf(NameHistoryDTO.Response.class, service().namesOf(OWNER, null, null));

        assertTrue(page.names().isEmpty(), "no history is an empty list, not an error");
        assertNull(page.playerUuid(), "there is no UUID to show");
    }

    @Test
    void aMalformedOwnerIsAValidationErrorForTheNames() {
        NameHistoryDTO result = service().namesOf("not-a-hash", null, null);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(NameHistoryDTO.Error.class, result).kind());
    }

    @Test
    void aPageSizeAboveTheMaximumIsAValidationError() {
        NameHistoryDTO result = service().namesOf(OWNER, 0, 101);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(NameHistoryDTO.Error.class, result).kind());
    }

    @Test
    void theOwnersOfAnOldNameAreFoundIgnoringCaseAndShowTheCurrentName() {
        NameHistoryService service = service();
        service.record(OWNER, PLAYER, "Steve", 1_000L);
        service.record(OWNER, PLAYER, "Alex", 2_000L);

        NameHistoryDTO.OwnerResponse found = assertInstanceOf(NameHistoryDTO.OwnerResponse.class, service.ownersOf("sTEVE"));

        assertEquals(1, found.owners().size(), "the one account that used the name");
        NameHistoryDTO.OwnerEntry owner = found.owners().getFirst();
        assertEquals(OWNER, owner.owner());
        assertEquals(PLAYER.toString(), owner.playerUuid());
        assertEquals("Alex", owner.currentName(), "the owner's newest name is shown");
        assertEquals(1_000L, owner.firstSeen(), "first seen of the matched name");
        assertEquals(1_000L, owner.lastSeen(), "last seen of the matched name");
    }

    @Test
    void aPartialNameFindsNothing() {
        service().record(OWNER, PLAYER, "Steve", 1_000L);

        NameHistoryDTO result = service().ownersOf("Ste");

        assertTrue(assertInstanceOf(NameHistoryDTO.OwnerResponse.class, result).owners().isEmpty(),
                "only an exact name matches");
    }

    @Test
    void aNameUsedByTwoAccountsListsBoth() {
        NameHistoryService service = service();
        service.record(OWNER, PLAYER, "Steve", 1_000L);
        service.record(OTHER_OWNER, UUID.randomUUID(), "Steve", 2_000L);

        NameHistoryDTO result = service.ownersOf("Steve");

        assertEquals(2, assertInstanceOf(NameHistoryDTO.OwnerResponse.class, result).owners().size(), "both accounts");
    }

    @Test
    void theOwnersLookupIsCappedAtTwentyOwners() {
        NameHistoryService service = service();
        for (int i = 0; i < 25; i++) {
            service.record("%0128x".formatted(i + 1), UUID.randomUUID(), "Steve", 1_000L + i);
        }

        NameHistoryDTO result = service.ownersOf("Steve");

        assertEquals(20, assertInstanceOf(NameHistoryDTO.OwnerResponse.class, result).owners().size(), "at most 20 owners");
    }

    @Test
    void anInvalidNameIsAValidationError() {
        NameHistoryDTO result = service().ownersOf("this-name-is-far-too-long");

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(NameHistoryDTO.Error.class, result).kind());
    }

    @Test
    void eraseOwnerRemovesItsNamesAndLeavesTheNamesOfOtherOwners() {
        NameHistoryService service = service();
        service.record(OWNER, PLAYER, "Steve", 1_000L);
        service.record(OWNER, PLAYER, "Alex", 2_000L);
        service.record(OTHER_OWNER, UUID.randomUUID(), "Steve", 3_000L);

        int erased = service.eraseOwner(OWNER);

        assertEquals(2, erased, "both names of the erased owner");
        assertEquals(1, store.all().size(), "the other owner keeps its name");
        assertEquals(OTHER_OWNER, store.all().getFirst().ownerHash());
    }

    @Test
    void aSwitchedOffServiceAnswersNotFoundAndReadsNothing() {
        NameHistoryService off = new NameHistoryService(store, false);
        store.upsert(OWNER, PLAYER, "Steve", 1_000L);

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(NameHistoryDTO.Error.class, off.namesOf(OWNER, null, null)).kind());
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(NameHistoryDTO.Error.class, off.ownersOf("Steve")).kind());
    }
}
