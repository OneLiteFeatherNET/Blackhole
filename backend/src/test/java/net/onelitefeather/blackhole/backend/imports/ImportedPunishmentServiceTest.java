package net.onelitefeather.blackhole.backend.imports;

import net.onelitefeather.blackhole.backend.imports.dto.ImportedPunishmentDTO;
import net.onelitefeather.blackhole.backend.imports.service.ImportTemplateResolver;
import net.onelitefeather.blackhole.backend.imports.service.ImportedPunishmentService;
import net.onelitefeather.blackhole.backend.imports.service.VanillaImportService;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportedPunishmentServiceTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final ImportedPunishmentService service = new ImportedPunishmentService(
            bed.punishments.repository(), bed.profiles.repository(),
            new ImportTemplateResolver(bed.templates.repository(), bed.eloBed.sections),
            bed.eloBed.events, bed.profileCache, bed.cases.timeline, bed.eloBed.clock, bed.audit);

    private PunishmentEntity givenImport(String id, String section, boolean active) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("reason", "Hacking");
        PunishmentEntity punishment = new PunishmentEntity(id, VanillaImportService.SYSTEM_IMPORT_SOURCE, OWNER, section, true, 1L, null, meta);
        bed.punishments.repository().save(punishment);
        Map<String, PunishmentEntity> slots = new HashMap<>();
        List<PunishmentEntity> history = new ArrayList<>();
        if (active) {
            slots.put(section, punishment);
        } else {
            history.add(punishment);
        }
        bed.profiles.repository().save(new PunishmentProfileEntity(OWNER, slots, history, new HashMap<>()));
        return punishment;
    }

    private ImportedPunishmentDTO retemplate(String templateKey, String reason) {
        return service.retemplate(new ImportedPunishmentDTO.RetemplateRequest(templateKey, reason, null), ACTOR);
    }

    @Test
    void anActiveBanIsAnnouncedAgainWithTheTemplateReasonSoTheProxyMirrorUpdates() {
        bed.givenTemplate("cheating", "network", null, step("P7D", 100));
        givenImport("imp-active", "network", true);

        retemplate("cheating", "Hacking");

        List<RecordingEventPublisher.Event> updated = bed.eloBed.events.eventsOfType("punishment.updated");
        assertEquals(1, updated.size(), "one punishment.updated for the active ban");
        assertEquals("imp-active", updated.getFirst().payload().get("punishmentIdentifier"));
        assertEquals("reason of cheating", updated.getFirst().payload().get("reason"), "the mirror carries the template reason");
        assertEquals(bed.templates.all().getFirst().getIdentifier().toString(), updated.getFirst().payload().get("templateIdentifier"));
    }

    @Test
    void aBanInHistoryIsLinkedWithoutAnEvent() {
        bed.givenTemplate("cheating", "network", null, step("P7D", 100));
        givenImport("imp-history", "network", false);

        ImportedPunishmentDTO result = retemplate("cheating", "Hacking");

        assertEquals(1, assertInstanceOf(ImportedPunishmentDTO.RetemplateResponse.class, result).retemplated());
        assertTrue(bed.eloBed.events.eventsOfType("punishment.updated").isEmpty(), "nothing is enforced, nothing to announce");
    }

    @Test
    void aPunishmentInAnotherSectionThanTheTemplateIsSkippedAndKeepsNoTemplate() {
        bed.givenSection("other-ban", EnforcementKind.BAN);
        bed.givenTemplate("elsewhere", "other-ban", null, step("P7D", 100));
        PunishmentEntity punishment = givenImport("imp-network", "network", true);

        ImportedPunishmentDTO result = retemplate("elsewhere", "Hacking");

        ImportedPunishmentDTO.RetemplateResponse response = assertInstanceOf(ImportedPunishmentDTO.RetemplateResponse.class, result);
        assertEquals(1, response.matched());
        assertEquals(1, response.skipped(), "the section of the punishment does not change");
        assertEquals(0, response.retemplated());
        assertNull(punishment.getTemplate());
        assertTrue(bed.eloBed.events.eventsOfType("punishment.updated").isEmpty());
    }
}
