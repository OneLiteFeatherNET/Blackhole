package net.onelitefeather.blackhole.backend.appealconditions;

import net.onelitefeather.blackhole.backend.appealconditions.dto.AppealConditionsDTO;
import net.onelitefeather.blackhole.backend.appealconditions.service.AppealConditionsService;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppealConditionsServiceTest {

    private static final UUID ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());

    /** An in-memory store that behaves like the table: versions only ever get appended. */
    private static final class MemoryStore implements AppealConditionsStore {
        final List<Version> versions = new ArrayList<>();
        boolean conflictOnNextAppend;

        @Override
        public Optional<Version> current() {
            return this.versions.stream().max(java.util.Comparator.comparingInt(Version::version));
        }

        @Override
        public List<Version> list() {
            return this.versions.stream().sorted(java.util.Comparator.comparingInt(Version::version).reversed()).toList();
        }

        @Override
        public Optional<Version> append(String text, UUID recordedByOid, long now) {
            if (this.conflictOnNextAppend) {
                this.conflictOnNextAppend = false;
                return Optional.empty();
            }
            Version created = new Version(this.versions.size() + 1, text, now, recordedByOid);
            this.versions.add(created);
            return Optional.of(created);
        }
    }

    private MemoryStore store;
    private TestClock clock;
    private AppealConditionsService service;

    @BeforeEach
    void givenAStoreHoldingTheDefaultVersion() {
        this.store = new MemoryStore();
        this.clock = new TestClock();
        this.store.versions.add(new AppealConditionsStore.Version(1, "default text", 1L, null));
        this.service = new AppealConditionsService(this.store, this.clock);
    }

    @Test
    void currentIsTheHighestVersion() {
        AppealConditionsDTO current = this.service.current();

        AppealConditionsDTO.Response response = assertInstanceOf(AppealConditionsDTO.Response.class, current);
        assertEquals(1, response.version());
        assertEquals("default text", response.text());
    }

    @Test
    void savingCreatesTheNextVersionAndKeepsTheOld() {
        AppealConditionsDTO saved = this.service.save(new AppealConditionsDTO.CreateRequest("new text"), ADMIN);

        assertEquals(2, assertInstanceOf(AppealConditionsDTO.Response.class, saved).version());
        assertEquals(List.of(2, 1), this.service.versions().stream().map(AppealConditionsDTO.Response::version).toList(), "newest first");
        assertEquals("default text", this.store.versions.getFirst().text(), "version 1 is unchanged");
    }

    @Test
    void theSavingActorAndTimeAreStored() {
        this.service.save(new AppealConditionsDTO.CreateRequest("new text"), ADMIN);

        AppealConditionsStore.Version stored = this.store.current().orElseThrow();
        assertEquals(ADMIN, stored.recordedByOid());
        assertEquals(this.clock.millis(), stored.createdAt());
    }

    @Test
    void textIsTrimmedAndLineEndingsAreNormalised() {
        this.service.save(new AppealConditionsDTO.CreateRequest("  one\r\n- two  \r\n"), ADMIN);

        assertEquals("one\n- two", this.store.current().orElseThrow().text());
    }

    @Test
    void anEmptyTextIsRefusedAndNothingIsStored() {
        assertRefused(new AppealConditionsDTO.CreateRequest("   \n "));
        assertRefused(new AppealConditionsDTO.CreateRequest(null));
        assertRefused(null);
    }

    @Test
    void aTextOverTheLimitIsRefused() {
        assertRefused(new AppealConditionsDTO.CreateRequest("x".repeat(AppealConditionsService.MAX_LENGTH + 1)));
    }

    @Test
    void aTextAtTheLimitIsAccepted() {
        AppealConditionsDTO saved = this.service.save(new AppealConditionsDTO.CreateRequest("x".repeat(AppealConditionsService.MAX_LENGTH)), ADMIN);

        assertInstanceOf(AppealConditionsDTO.Response.class, saved);
    }

    @Test
    void aControlCharacterIsRefused() {
        assertRefused(new AppealConditionsDTO.CreateRequest("bell\u0007"));
        assertRefused(new AppealConditionsDTO.CreateRequest("tab\there"));
    }

    @Test
    void aLostRaceIsAConflict() {
        this.store.conflictOnNextAppend = true;

        AppealConditionsDTO saved = this.service.save(new AppealConditionsDTO.CreateRequest("new text"), ADMIN);

        assertEquals(ErrorKind.CONFLICT, assertInstanceOf(AppealConditionsDTO.Error.class, saved).kind());
        assertEquals(1, this.store.versions.size(), "nothing was appended");
    }

    private void assertRefused(AppealConditionsDTO.CreateRequest request) {
        int before = this.store.versions.size();
        AppealConditionsDTO saved = this.service.save(request, ADMIN);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(AppealConditionsDTO.Error.class, saved).kind());
        assertEquals(before, this.store.versions.size(), "no version was created");
        assertTrue(this.store.versions.stream().noneMatch(v -> v.version() > before));
    }
}
