package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentReader;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentSort;
import net.onelitefeather.blackhole.backend.punishment.SortDirection;
import net.onelitefeather.blackhole.backend.punishment.dto.ActivePunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The rules of the overview service over a recording reader: defaults, clamping, validation and mapping. No database. */
class ActivePunishmentServiceTest {

    private final TestClock clock = new TestClock();
    private final List<ActivePunishmentReader.Query> queries = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private List<ActivePunishmentReader.Row> rows = List.of();
    private long total;
    private final ActivePunishmentService service = new ActivePunishmentService((query, now) -> {
        this.queries.add(query);
        this.times.add(now);
        return new ActivePunishmentReader.Page(this.rows, this.total);
    }, this.clock);

    private ActivePunishmentDTO listDefaults() {
        return this.service.list(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void withoutParametersItListsTheNewestFirstOnPageZero() {
        listDefaults();

        ActivePunishmentReader.Query query = this.queries.getFirst();
        assertEquals(ActivePunishmentSort.CREATED, query.sort());
        assertEquals(SortDirection.DESC, query.direction());
        assertEquals(0, query.page());
        assertEquals(ActivePunishmentService.DEFAULT_SIZE, query.size());
    }

    @Test
    void itAsksTheReaderForTheInjectedClocksTime() {
        listDefaults();

        assertEquals(this.clock.millis(), this.times.getFirst());
    }

    @Test
    void anOversizedPageIsClampedAndATinyOneRaised() {
        this.service.list(null, null, null, null, null, null, null, null, null, 0, 5000);
        this.service.list(null, null, null, null, null, null, null, null, null, 0, 0);

        assertEquals(ActivePunishmentService.MAX_SIZE, this.queries.get(0).size());
        assertEquals(1, this.queries.get(1).size());
    }

    @Test
    void aBlankSectionDoesNotFilter() {
        this.service.list(null, "  ", null, null, null, null, null, null, null, null, null);

        assertNull(this.queries.getFirst().section());
    }

    @Test
    void aNegativeExpiryBoundIsRejectedWithoutReading() {
        ActivePunishmentDTO result = this.service.list(null, null, null, null, -1L, null, null, null, null, null, null);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(ActivePunishmentDTO.Error.class, result).kind());
        assertEquals(0, this.queries.size(), "a rejected request reads nothing");
    }

    @Test
    void aNegativePageIsRejected() {
        ActivePunishmentDTO result = this.service.list(null, null, null, null, null, null, null, null, null, -1, null);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(ActivePunishmentDTO.Error.class, result).kind());
    }

    @Test
    void rowsAreMappedAndPagesCounted() {
        this.rows = List.of(new ActivePunishmentReader.Row("o".repeat(128), "id-1", "#ABCDEF01", "network", "Network", EnforcementKind.BAN,
                null, "grief", "Griefing", false, 5L, 9L, false, "IN_REVIEW", "Alice"));
        this.total = 26;

        ActivePunishmentDTO.ListPage page = assertInstanceOf(ActivePunishmentDTO.ListPage.class, this.service.list(
                EnforcementKind.BAN, null, null, null, null, null, null, null, null, null, 10));

        assertEquals(1, page.items().size());
        assertEquals(CaseStatus.IN_REVIEW, page.items().getFirst().caseStatus());
        assertEquals("Alice", page.items().getFirst().actorName());
        assertEquals(26, page.totalSize());
        assertEquals(3, page.totalPages());
        assertEquals(EnforcementKind.BAN, this.queries.getFirst().kind());
    }
}
