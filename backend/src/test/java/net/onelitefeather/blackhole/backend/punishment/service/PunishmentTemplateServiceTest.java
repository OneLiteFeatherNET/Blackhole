package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentTemplateServiceTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final PunishmentTemplateService service = bed.templateService;

    private static final List<PunishTemplateDTO.Step> LADDER = List.of(
            new PunishTemplateDTO.Step("P1D", 100), new PunishTemplateDTO.Step("P7D", 150), new PunishTemplateDTO.Step("P30D", 250));

    private static PunishTemplateDTO.CreateRequest hacking(List<PunishTemplateDTO.Step> steps) {
        return new PunishTemplateDTO.CreateRequest("hacking", "Hacking", "network", null, null, steps, null);
    }

    private PunishTemplateDTO.Error expectError(PunishTemplateDTO result, ErrorKind kind) {
        PunishTemplateDTO.Error error = assertInstanceOf(PunishTemplateDTO.Error.class, result, "expected an error but got " + result);
        assertEquals(kind, error.kind(), "kind of: " + error.errorMessage());
        return error;
    }

    @Test
    void aCreatedTemplateKeepsItsStepsInOrderAndDefaultsTheGroupToItsKey() {
        PunishTemplateDTO.Response created = assertInstanceOf(PunishTemplateDTO.Response.class, service.create(hacking(LADDER)));

        assertEquals(LADDER, created.steps(), "steps in the submitted order");
        assertEquals("hacking", created.escalationGroup(), "the group defaults to the key");
        assertEquals("network", created.section());
        assertEquals(1, bed.templates.all().size());
    }

    @Test
    void anExplicitEscalationGroupIsKept() {
        PunishTemplateDTO.CreateRequest request = new PunishTemplateDTO.CreateRequest(
                "killaura", "Killaura", "network", "hacking", "P90D", LADDER, Map.of("translatable", true));

        PunishTemplateDTO.Response created = assertInstanceOf(PunishTemplateDTO.Response.class, service.create(request));

        assertEquals("hacking", created.escalationGroup());
        assertEquals("P90D", created.escalationWindow());
        assertEquals(Map.of("translatable", true), created.metaData());
    }

    @Test
    void aTemplateWithoutStepsIsRejectedBecausePermanentTemplatesDoNotExist() {
        expectError(service.create(hacking(List.of())), ErrorKind.VALIDATION);
        assertTrue(bed.templates.all().isEmpty(), "nothing stored");
    }

    @Test
    void aStepWithoutADurationIsRejected() {
        expectError(service.create(hacking(List.of(new PunishTemplateDTO.Step(" ", 10)))), ErrorKind.VALIDATION);
    }

    @Test
    void aStepWithAZeroOrNegativeDurationIsRejected() {
        expectError(service.create(hacking(List.of(new PunishTemplateDTO.Step("PT0S", 10)))), ErrorKind.VALIDATION);
        expectError(service.create(hacking(List.of(new PunishTemplateDTO.Step("-P1D", 10)))), ErrorKind.VALIDATION);
    }

    @Test
    void aStepWithANegativeEloCostIsRejected() {
        expectError(service.create(hacking(List.of(new PunishTemplateDTO.Step("P1D", -1)))), ErrorKind.VALIDATION);
    }

    @Test
    void aStepWithAnEloCostOfZeroIsAccepted() {
        assertInstanceOf(PunishTemplateDTO.Response.class, service.create(hacking(List.of(new PunishTemplateDTO.Step("P1D", 0)))));
    }

    @Test
    void aTemplateInAnUnknownSectionIsRejectedAndNothingIsStored() {
        PunishTemplateDTO.CreateRequest request = new PunishTemplateDTO.CreateRequest("hacking", "Hacking", "ghost", null, null, LADDER, null);

        expectError(service.create(request), ErrorKind.VALIDATION);
        assertTrue(bed.templates.all().isEmpty());
    }

    @Test
    void aKeyThatIsNotKebabCaseIsRejected() {
        PunishTemplateDTO.CreateRequest request = new PunishTemplateDTO.CreateRequest("Hack Ing", "Hacking", "network", null, null, LADDER, null);

        expectError(service.create(request), ErrorKind.VALIDATION);
    }

    @Test
    void aKeyThatAnotherTemplateUsesIsAConflict() {
        service.create(hacking(LADDER));

        expectError(service.create(hacking(LADDER)), ErrorKind.CONFLICT);
        assertEquals(1, bed.templates.all().size(), "no second template");
    }

    @Test
    void updatingATemplateReplacesItsFieldsAndKeepsItsIdentifier() {
        PunishTemplateDTO.Response created = assertInstanceOf(PunishTemplateDTO.Response.class, service.create(hacking(LADDER)));
        PunishTemplateDTO.UpdateRequest update = new PunishTemplateDTO.UpdateRequest(
                created.identifier(), "hacking", "Hacking v2", "network", null, null, List.of(new PunishTemplateDTO.Step("P2D", 5)), null);

        PunishTemplateDTO.Response updated = assertInstanceOf(PunishTemplateDTO.Response.class, service.update(update));

        assertEquals(created.identifier(), updated.identifier());
        assertEquals("Hacking v2", updated.reason());
        assertEquals(1, updated.steps().size());
    }

    @Test
    void updatingATemplateWithItsOwnKeyIsNotAConflict() {
        PunishTemplateDTO.Response created = assertInstanceOf(PunishTemplateDTO.Response.class, service.create(hacking(LADDER)));

        assertInstanceOf(PunishTemplateDTO.Response.class, service.update(new PunishTemplateDTO.UpdateRequest(
                created.identifier(), "hacking", "Hacking", "network", null, null, LADDER, null)));
    }

    @Test
    void updatingATemplateToTheKeyOfAnotherIsAConflict() {
        service.create(hacking(LADDER));
        PunishTemplateDTO.Response other = assertInstanceOf(PunishTemplateDTO.Response.class, service.create(
                new PunishTemplateDTO.CreateRequest("spam", "Spam", "chat", null, null, LADDER, null)));

        expectError(service.update(new PunishTemplateDTO.UpdateRequest(
                other.identifier(), "hacking", "Spam", "chat", null, null, LADDER, null)), ErrorKind.CONFLICT);
    }

    @Test
    void updatingAMissingTemplateIsNotFound() {
        expectError(service.update(new PunishTemplateDTO.UpdateRequest(
                java.util.UUID.randomUUID(), "hacking", "Hacking", "network", null, null, LADDER, null)), ErrorKind.NOT_FOUND);
    }

    @Test
    void removingATemplateThatNoPunishmentUsesDeletesIt() {
        PunishmentTemplateEntity template = bed.givenTemplate("spam", "chat", null, step("PT1H", 10));

        assertInstanceOf(PunishTemplateDTO.Response.class, service.remove(template.getIdentifier()));
        assertTrue(bed.templates.all().isEmpty());
    }

    @Test
    void removingAMissingTemplateIsNotFound() {
        expectError(service.remove(java.util.UUID.randomUUID()), ErrorKind.NOT_FOUND);
    }

    @Test
    void removingATemplateThatAPunishmentReferencesIsAConflict() {
        PunishmentTemplateEntity template = bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        bed.punishmentService.apply(new net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO.ApplyRequest(
                PunishmentTestBed.OWNER, "spam", null, PunishmentTestBed.STAFF));

        expectError(service.remove(template.getIdentifier()), ErrorKind.CONFLICT);
        assertEquals(1, bed.templates.all().size(), "the template stays");
    }

    @Test
    void updatingATemplateEvictsItAndTheList() {
        PunishmentTemplateEntity template = bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        bed.templateCache.put(template.getIdentifier(), template.toDTO());
        bed.templateCache.putAll(List.of(template.toDTO()));

        service.update(new PunishTemplateDTO.UpdateRequest(
                template.getIdentifier(), "spam", "Spam v2", "chat", null, null, LADDER, null));

        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.template(template.getIdentifier())));
        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.TEMPLATES_ALL));
    }

    @Test
    void removingATemplateEvictsItAndTheList() {
        PunishmentTemplateEntity template = bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        bed.templateCache.put(template.getIdentifier(), template.toDTO());
        bed.templateCache.putAll(List.of(template.toDTO()));

        service.remove(template.getIdentifier());

        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.template(template.getIdentifier())));
        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.TEMPLATES_ALL));
    }

    @Test
    void creatingATemplateEvictsTheList() {
        bed.templateCache.putAll(List.of());

        service.create(hacking(LADDER));

        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.TEMPLATES_ALL));
    }

    @Test
    void aTemplateWithoutAnExplicitWindowStoresNone() {
        PunishTemplateDTO.Response created = assertInstanceOf(PunishTemplateDTO.Response.class, service.create(hacking(LADDER)));

        assertNull(created.escalationWindow(), "no explicit window: the configured default applies at apply time");
    }
}
