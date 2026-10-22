package net.onelitefeather.blackhole.backend.punishment.service;

import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The evidence count on punishment entries and appeals, and that nothing else about evidence is exposed. */
class PunishmentEvidenceCountTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @BeforeEach
    void givenTemplate() {
        bed.givenTemplate("spam", "network", null, step("P1D", 100));
    }

    private PunishEntryDTO applied() {
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, STAFF));
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class, result).punishment();
    }

    private static List<String> evidenceComponents(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .filter(name -> name.toLowerCase().contains("evidence")).toList();
    }

    @Test
    void anAppliedPunishmentStartsWithoutEvidence() {
        assertEquals(0L, applied().evidenceCount());
    }

    @Test
    void theShortIdLookupCarriesTheNumberOfValidEvidenceItems() {
        PunishEntryDTO punishment = applied();
        bed.evidence.givenCount(punishment.identifier(), 3);

        PunishmentDTO.ShortIdMatches found = assertInstanceOf(PunishmentDTO.ShortIdMatches.class,
                bed.punishmentService.findByShortId(punishment.shortId()));

        assertEquals(3L, found.punishments().getFirst().evidenceCount());
    }

    @Test
    void theListingCarriesTheNumberOfValidEvidenceItems() {
        PunishEntryDTO punishment = applied();
        bed.evidence.givenCount(punishment.identifier(), 2);

        PunishEntryDTO listed = bed.punishmentService.findAll(Pageable.from(0, 10)).getContent().getFirst();

        assertEquals(2L, listed.evidenceCount());
    }

    @Test
    void anEntryBuiltWithoutLookupHasNoCountRatherThanAWrongZero() {
        applied();

        PunishEntryDTO plain = bed.punishments.all().getFirst().toDTO();

        assertNull(plain.evidenceCount(), "profile listings do not compute the count");
    }

    @Test
    void punishmentEntriesExposeNoEvidenceFieldBesidesTheCount() {
        assertEquals(List.of("evidenceCount"), evidenceComponents(PunishEntryDTO.class));
    }

    @Test
    void appealsExposeNoEvidenceFieldBesidesTheCount() {
        assertEquals(List.of("evidenceCount"), evidenceComponents(AppealDTO.class));
    }
}
