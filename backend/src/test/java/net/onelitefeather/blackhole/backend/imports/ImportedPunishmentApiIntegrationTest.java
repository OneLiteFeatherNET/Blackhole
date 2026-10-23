package net.onelitefeather.blackhole.backend.imports;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.elo.EloScoreRepository;
import net.onelitefeather.blackhole.backend.imports.service.VanillaImportService;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class ImportedPunishmentApiIntegrationTest extends ApiIntegrationTest {

    private static final UUID ADMIN_OID = UUID.fromString("ad000000-0000-4000-8000-000000000001");

    private String namedAdmin() {
        return this.jwt.delegated().groups(TestJwtIssuer.ADMIN_GROUP).claim("oid", ADMIN_OID.toString()).sign();
    }

    private void givenTemplate(String key, String section) {
        Map<String, Object> body = Map.of("key", key, "reason", "Reason of " + key, "section", section,
                "steps", List.of(Map.of("duration", "P7D", "eloCost", 100)));
        assertEquals(200, post("/template", body).status());
    }

    /** A ban imported before imports needed a template: the import identity as source, no template, the reason in the metadata. */
    private String givenLegacyImport(String player, String reason) {
        String owner = UUIDHasher.hash(UUID.nameUUIDFromBytes(("retemplate-" + player).getBytes()));
        String id = UUID.randomUUID().toString();
        Map<String, Object> meta = new HashMap<>();
        meta.put("creation_date", 1L);
        meta.put("update_date", 1L);
        meta.put("reason", reason);
        meta.put("importSource", "vanilla");
        PunishmentEntity punishment = this.context.getBean(PunishmentRepository.class).save(
                new PunishmentEntity(id, VanillaImportService.SYSTEM_IMPORT_SOURCE, owner, "network", true, 1L, null, meta));
        Map<String, PunishmentEntity> active = new HashMap<>();
        active.put("network", punishment);
        this.context.getBean(PunishmentProfileRepository.class).save(new PunishmentProfileEntity(owner, active, new java.util.ArrayList<>(), new HashMap<>()));
        return id;
    }

    private PunishmentEntity stored(String id) {
        return this.context.getBean(PunishmentRepository.class).findById(id).orElseThrow();
    }

    private Reply retemplate(Map<String, Object> body, String token) {
        return post("/admin/import/punishments/retemplate", body, token);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groups() {
        return (List<Map<String, Object>>) get("/admin/import/punishments").json().get("groups");
    }

    @Test
    void theGroupsListUnlinkedImportsByOriginalReasonLargestFirst() {
        givenLegacyImport("grp-a", "grp-reason one");
        givenLegacyImport("grp-b", "grp-reason two");
        givenLegacyImport("grp-c", "grp-reason two");

        List<Map<String, Object>> mine = groups().stream().filter(g -> String.valueOf(g.get("reason")).startsWith("grp-reason")).toList();

        assertEquals(List.of(Map.of("reason", "grp-reason two", "count", 2), Map.of("reason", "grp-reason one", "count", 1)), mine);
    }

    @Test
    void retemplatingByReasonLinksTheTemplateKeepsTheReasonAndChangesNoScore() {
        givenTemplate("rt-by-reason", "network");
        String a = givenLegacyImport("rbr-a", "rbr reason");
        String b = givenLegacyImport("rbr-b", "rbr reason");
        String other = givenLegacyImport("rbr-c", "rbr other reason");

        Reply reply = retemplate(Map.of("templateKey", "rt-by-reason", "reason", "rbr reason"), namedAdmin());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(Map.of("templateKey", "rt-by-reason", "matched", 2, "retemplated", 2, "unchanged", 0, "skipped", 0), reply.json());
        assertEquals("rt-by-reason", stored(a).getTemplate().getTemplateKey());
        assertEquals("rt-by-reason", stored(b).getTemplate().getTemplateKey());
        assertEquals("rbr reason", stored(a).getMetaData().get("importedReason"), "the original reason is kept for staff");
        assertEquals(null, stored(other).getTemplate(), "another reason is left alone");
        assertNotNull(stored(a).getOwner());
        assertTrue(this.context.getBean(EloScoreRepository.class).findByIdOwner(stored(a).getOwner()).isEmpty(), "no score changed");
        assertEquals(null, stored(a).getEloCost(), "no cost or step was recorded");
    }

    @Test
    void theCaseTimelineRecordsTheRetemplateWithTheActorOfTheToken() {
        givenTemplate("rt-case", "network");
        String id = givenLegacyImport("case-a", "case reason");

        retemplate(Map.of("templateKey", "rt-case", "identifiers", List.of(id)), namedAdmin());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) get("/case/" + id + "/timeline").json().get("items");
        Map<String, Object> event = items.stream().filter(i -> "punishment.retemplated".equals(i.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> actor = (Map<String, Object>) event.get("actor");
        assertEquals("VERIFIED", actor.get("trust"));
        assertEquals(ADMIN_OID.toString(), actor.get("oid"));
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertNotNull(payload.get("templateId"));
    }

    @Test
    void repeatingARequestChangesNothing() {
        givenTemplate("rt-idem", "network");
        String id = givenLegacyImport("idem-a", "idem reason");
        retemplate(Map.of("templateKey", "rt-idem", "reason", "idem reason"), namedAdmin());

        Reply again = retemplate(Map.of("templateKey", "rt-idem", "reason", "idem reason"), namedAdmin());
        Reply againById = retemplate(Map.of("templateKey", "rt-idem", "identifiers", List.of(id)), namedAdmin());

        assertEquals(0, again.json().get("retemplated"));
        assertEquals(0, again.json().get("matched"), "a linked punishment is no longer selected by reason");
        assertEquals(0, againById.json().get("retemplated"));
        assertEquals(1, againById.json().get("unchanged"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) get("/case/" + id + "/timeline").json().get("items");
        assertEquals(1, items.stream().filter(i -> "punishment.retemplated".equals(i.get("type"))).count(), "one event, however often it is asked");
    }

    @Test
    void aSelectionNeverOverwritesAnotherTemplateAndCountsUnknownIdentifiersAsSkipped() {
        givenTemplate("rt-first", "network");
        givenTemplate("rt-second", "network");
        String id = givenLegacyImport("sel-a", "sel reason");
        retemplate(Map.of("templateKey", "rt-first", "identifiers", List.of(id)), namedAdmin());

        Reply reply = retemplate(Map.of("templateKey", "rt-second", "identifiers", List.of(id, "no-such-punishment")), namedAdmin());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(2, reply.json().get("matched"));
        assertEquals(0, reply.json().get("retemplated"));
        assertEquals(2, reply.json().get("skipped"));
        assertEquals("rt-first", stored(id).getTemplate().getTemplateKey());
    }

    @Test
    void aPunishmentThatIsNotAnImportIsSkipped() {
        givenTemplate("rt-notimport", "network");
        String id = UUID.randomUUID().toString();
        this.context.getBean(PunishmentRepository.class).save(
                new PunishmentEntity(id, UUID.randomUUID(), "ab".repeat(64), "network", false, 1L, null, new HashMap<>()));

        Reply reply = retemplate(Map.of("templateKey", "rt-notimport", "identifiers", List.of(id)), namedAdmin());

        assertEquals(1, reply.json().get("skipped"));
        assertEquals(null, stored(id).getTemplate());
    }

    @Test
    void aTemplateInAMuteSectionIsRejectedAndNothingChanges() {
        givenTemplate("rt-mute", "chat");
        String id = givenLegacyImport("mute-a", "mute reason");

        Reply reply = retemplate(Map.of("templateKey", "rt-mute", "reason", "mute reason"), namedAdmin());

        assertEquals(400, reply.status(), reply.body());
        assertEquals(null, stored(id).getTemplate());
    }

    @Test
    void anUnknownTemplateIsNotFound() {
        assertEquals(404, retemplate(Map.of("templateKey", "rt-nope", "reason", "x"), namedAdmin()).status());
    }

    @Test
    void exactlyOneOfReasonAndIdentifiersIsRequired() {
        givenTemplate("rt-either", "network");

        assertEquals(400, retemplate(Map.of("templateKey", "rt-either"), namedAdmin()).status());
        assertEquals(400, retemplate(Map.of("templateKey", "rt-either", "reason", "x", "identifiers", List.of("a")), namedAdmin()).status());
    }

    @Test
    void onlyAnAdminMayListOrRetemplate() {
        givenTemplate("rt-roles", "network");
        String id = givenLegacyImport("roles-a", "roles reason");

        assertEquals(403, get("/admin/import/punishments", this.jwt.moderator()).status());
        assertEquals(403, retemplate(Map.of("templateKey", "rt-roles", "identifiers", List.of(id)), this.jwt.moderator()).status());
        assertEquals(403, retemplate(Map.of("templateKey", "rt-roles", "identifiers", List.of(id)), this.jwt.viewer()).status());
        assertEquals(null, stored(id).getTemplate());
    }
}
