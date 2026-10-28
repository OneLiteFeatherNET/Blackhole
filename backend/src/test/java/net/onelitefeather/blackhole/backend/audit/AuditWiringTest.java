package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.json.JsonMapper;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.appeal.service.AppealDecisionService;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.imports.dto.ImportedPunishmentDTO;
import net.onelitefeather.blackhole.backend.imports.dto.VanillaImportDTO;
import net.onelitefeather.blackhole.backend.imports.service.ImportTemplateResolver;
import net.onelitefeather.blackhole.backend.imports.service.ImportedPunishmentService;
import net.onelitefeather.blackhole.backend.imports.service.VanillaImportService;
import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.report.CategorySectionMapping;
import net.onelitefeather.blackhole.backend.report.ReportCategory;
import net.onelitefeather.blackhole.backend.report.ReportEntity;
import net.onelitefeather.blackhole.backend.report.ReportRepository;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.blackhole.backend.report.dto.ReportResolutionDTO;
import net.onelitefeather.blackhole.backend.report.feedback.ReportFeedbackEntity;
import net.onelitefeather.blackhole.backend.report.feedback.ReportFeedbackRepository;
import net.onelitefeather.blackhole.backend.report.feedback.ReportFeedbackService;
import net.onelitefeather.blackhole.backend.report.service.ReportService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.DiscordLinkTestBed;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEloScoring;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingProtocols;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.PROXY;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The staff writes of kicks, punishments and staff links each leave exactly one audit entry when they succeed. */
class AuditWiringTest {

    private static final UUID REVIEWER = UUID.nameUUIDFromBytes("reviewer".getBytes());
    private static final UUID MODERATOR = UUID.nameUUIDFromBytes("moderator".getBytes());
    private static final String REPORTED = "aa".repeat(64);
    private static final String REPORTER = "bb".repeat(64);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final AppealDecisionService appealDecisions = new AppealDecisionService(
            appeals.repository(), this.bed.punishments.repository(), this.bed.profiles.repository(), this.bed.slots, this.bed.profileCache,
            this.bed.elo, this.bed.eloBed.sections, this.bed.eloBed.events, this.bed.evidence, this.bed.staffLinks.service,
            this.bed.cases.timeline, this.bed.banIds.service, new RecordingProtocols(), this.bed.staffLinks.profileService,
            this.bed.eloBed.clock, this.bed.audit);
    private final InMemoryRepository<ReportRepository, ReportEntity, UUID> reports =
            InMemoryRepository.<ReportRepository, ReportEntity, UUID>of(ReportRepository.class, ReportEntity::getIdentifier).build();
    private final InMemoryRepository<ReportFeedbackRepository, ReportFeedbackEntity, UUID> feedback =
            InMemoryRepository.<ReportFeedbackRepository, ReportFeedbackEntity, UUID>of(ReportFeedbackRepository.class, ReportFeedbackEntity::getIdentifier)
                    .assigningIds(entity -> entity.withIdentifier(UUID.randomUUID())).build();
    private final RecordingEventPublisher reportEvents = new RecordingEventPublisher();
    private final ReportService reportService = new ReportService(
            reports.repository(), new ReportFeedbackService(feedback.repository(), new ArrayList<String>()::add, reportEvents,
            this.bed.eloBed.clock, Duration.ofDays(14)), this.bed.evidence, reportEvents, this.bed.punishmentService, new RecordingEloScoring(),
            new CategorySectionMapping(Map.of("CHEATING", "network")), this.bed.eloBed.clock, 5, 50, Duration.ofMinutes(10), -100, 50, this.bed.audit);

    @Test
    void aKickRecordsOneKickCreateEntryForTheTargetedPlayer() {
        KickDTO result = this.bed.kickService.kick(new KickDTO.CreateRequest(OWNER, null, null, "spamming"), "key-1", ACTOR);

        assertInstanceOf(KickDTO.Accepted.class, result, "the kick was accepted");
        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("kick.create", row.action());
        assertEquals("PLAYER", row.targetKind());
        assertEquals(OWNER, row.targetRef());
        assertEquals(ACTOR.oid(), row.actorOid());
    }

    @Test
    void aRefusedKickRecordsNothing() {
        this.bed.kickService.kick(new KickDTO.CreateRequest(OWNER, KickScope.SECTION, "no-such-section", "spamming"), "key-2", ACTOR);

        assertTrue(this.bed.auditStore.rows().isEmpty(), "a refused kick is not audited");
    }

    @Test
    void aRevokeRecordsOnePunishmentRevokeEntryForTheCase() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);

        this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        assertEquals(2, this.bed.auditStore.rows().size(), "the apply and the revoke are each audited once");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("punishment.revoke", row.action());
        assertEquals("CASE", row.targetKind());
        assertTrue(row.targetRef() != null && row.targetRef().length() >= 8, "the case short id is the target");
    }

    @Test
    void aFailingAuditInsertDoesNotUndoTheRevoke() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
        this.bed.auditStore.failInserts(true);

        PunishmentDTO result = this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        assertInstanceOf(PunishmentDTO.RevokeResponse.class, result, "the revoke still succeeds");
        assertEquals(1, this.bed.auditStore.rows().size(), "the failed revoke left no row; only the earlier apply is stored");
    }

    @Test
    void aProxyVouchingForStaffRecordsInGameWithTheStaffAsActor() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        UUID inGameStaff = UUID.nameUUIDFromBytes("in-game-staff".getBytes());
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), PROXY);

        this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", inGameStaff), PROXY);

        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals(Surface.IN_GAME, row.surface());
        assertEquals(inGameStaff, row.actorOid());
        assertEquals(PROXY.oid(), row.recordedByOid());
    }

    @Test
    void anAdminUnlinkRecordsOneStaffLinkUnlinkEntry() {
        UUID staff = UUID.nameUUIDFromBytes("linked-staff".getBytes());
        this.bed.staffLinks.givenLink(staff, UUID.nameUUIDFromBytes("minecraft".getBytes()));
        Actor admin = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false, true).withPanel(true);

        this.bed.staffLinks.service.unlink(staff, admin);

        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("staff-link.unlink", row.action());
        assertEquals(Surface.PANEL, row.surface());
        assertEquals(admin.oid(), row.actorOid());
    }

    @Test
    void anApplyRecordsOnePunishmentApplyEntryNamedByTheCaseShortId() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);

        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry for the apply");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("punishment.apply", row.action());
        assertEquals("CASE", row.targetKind());
        assertFalse(row.targetRef().contains(OWNER), "the case is named by its short id, not the owner hash");
    }

    @Test
    void aTemplateRemoveRecordsOneTemplateRemoveEntryNamedByKey() {
        var template = this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        this.bed.templateService.remove(template.getIdentifier(), ACTOR);

        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry for the removal");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("template.remove", row.action());
        assertEquals("TEMPLATE", row.targetKind());
        assertEquals("spam", row.targetRef());
    }

    @Test
    void anEloAdjustRecordsOneEloAdjustEntryForThePlayer() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        this.bed.elo.adjust(OWNER, new EloDTO.AdjustRequest("chat", 10, null), ACTOR);

        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry for the adjustment");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("elo.adjust", row.action());
        assertEquals("PLAYER", row.targetKind());
        assertEquals(OWNER, row.targetRef());
    }

    @Test
    void anAppealDecisionRecordsOneAppealReviewEntryForTheCase() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
        PunishmentEntity mute = this.bed.punishments.all().getFirst();
        UUID appealId = UUID.randomUUID();
        this.appeals.given(new AppealEntity(mute, OWNER, "please", AppealStatus.ELIGIBLE_PENDING_REVIEW,
                new HashMap<>(Map.of("severityTier", "STANDARD")), 0L, 0L, new HashMap<>()), appealId);

        this.appealDecisions.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(AppealStatus.DENIED, "a note", null),
                new Actor(REVIEWER, "Reviewer", ActorKind.HUMAN, false));

        List<StaffAuditEntry> rows = this.bed.auditStore.rows();
        assertEquals(2, rows.size(), "the apply and the appeal decision are each audited once");
        StaffAuditEntry row = rows.getLast();
        assertEquals("appeal.review", row.action());
        assertEquals("CASE", row.targetKind());
        assertEquals(mute.getShortId(), row.targetRef(), "the case is named by its short id");
        assertEquals(REVIEWER, row.actorOid());
        assertNoBanIdOrIp(row, mute.getIdentifier());
    }

    @Test
    void anActionedReportWithATemplateRecordsThePunishmentThenTheResolution() {
        UUID reportId = UUID.randomUUID();
        this.reports.given(new ReportEntity(REPORTER, REPORTED, ReportCategory.CHEATING, "d", new ArrayList<>(), ReportStatus.OPEN,
                0L, 0L, null, null, new HashMap<>()), reportId);
        var template = this.bed.givenTemplate("grief", "network", null, step("P3D", 0));

        this.reportService.resolve(reportId, new ReportResolutionDTO(ReportStatus.ACTIONED, "note", template.getIdentifier()),
                new Actor(MODERATOR, "Moderator", ActorKind.HUMAN, false));

        List<StaffAuditEntry> rows = this.bed.auditStore.rows();
        assertEquals(2, rows.size(), "the punishment and the report resolution are each audited once");
        assertEquals("punishment.apply", rows.getFirst().action());
        StaffAuditEntry row = rows.getLast();
        assertEquals("report.resolve", row.action());
        assertEquals("PLAYER", row.targetKind());
        assertEquals(REPORTED, row.targetRef(), "the report is named by the reported player's hash");
        assertEquals(MODERATOR, row.actorOid());
    }

    @Test
    void attachingEvidenceRecordsOneEvidenceAttachEntryForThePlayer(@TempDir Path spool) {
        EvidenceTestBed evidence = new EvidenceTestBed(spool);

        assertInstanceOf(EvidenceDTO.Response.class, evidence.service.attach(new EvidenceDTO.CreateRequest(
                null, EvidenceTestBed.OWNER, "c1", EvidenceKind.LINK, "test", null, "https://example.org/a", null, null), EvidenceTestBed.ACTOR));

        List<StaffAuditEntry> rows = evidence.staffAuditStore.rows();
        assertEquals(1, rows.size(), "exactly one audit entry");
        StaffAuditEntry row = rows.getLast();
        assertEquals("evidence.attach", row.action());
        assertEquals("PLAYER", row.targetKind());
        assertEquals(EvidenceTestBed.OWNER, row.targetRef());
        assertEquals(EvidenceTestBed.ACTOR.oid(), row.actorOid());
    }

    @Test
    void deletingEvidenceRecordsOneEvidenceDeleteEntryForThePlayer(@TempDir Path spool) {
        EvidenceTestBed evidence = new EvidenceTestBed(spool);
        EvidenceDTO.Response attached = assertInstanceOf(EvidenceDTO.Response.class, evidence.service.attach(new EvidenceDTO.CreateRequest(
                null, EvidenceTestBed.OWNER, "c1", EvidenceKind.LINK, "test", null, "https://example.org/a", null, null), EvidenceTestBed.ACTOR));

        assertInstanceOf(EvidenceDTO.DeleteResponse.class,
                evidence.service.delete(attached.identifier(), "WRONG_EVIDENCE", null, EvidenceTestBed.ACTOR));

        List<StaffAuditEntry> rows = evidence.staffAuditStore.rows();
        assertEquals(2, rows.size(), "the attach and the delete are each audited once");
        StaffAuditEntry row = rows.getLast();
        assertEquals("evidence.delete", row.action());
        assertEquals("PLAYER", row.targetKind());
        assertEquals(EvidenceTestBed.OWNER, row.targetRef());
        assertEquals(EvidenceTestBed.ACTOR.oid(), row.actorOid());
    }

    @Test
    void aVanillaImportRecordsOneImportVanillaEntryWithoutTheImportedPlayersUuid() throws Exception {
        this.bed.givenTemplate("griefing", "network", null, step("P7D", 100));
        UUID player = UUID.nameUUIDFromBytes("imported-player".getBytes());
        String players = "[{\"uuid\":\"" + player + "\",\"name\":\"Griefer\",\"created\":\"2026-01-01 10:00:00 +0000\","
                + "\"source\":\"Console\",\"expires\":\"forever\",\"reason\":\"Griefing\"}]";
        VanillaImportService importer = new VanillaImportService(JsonMapper.createDefault(), this.bed.punishments.repository(),
                this.bed.profiles.repository(), this.bed.eloBed.events, this.bed.profileCache,
                new ImportTemplateResolver(this.bed.templates.repository(), this.bed.eloBed.sections), this.bed.cases.timeline,
                this.bed.eloBed.clock, this.bed.audit);

        assertInstanceOf(VanillaImportDTO.Result.class,
                importer.importVanillaBans(players.getBytes(StandardCharsets.UTF_8), null, "griefing", null, false, ACTOR));

        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("import.vanilla", row.action());
        assertEquals("NONE", row.targetKind());
        assertEquals(ACTOR.oid(), row.actorOid());
        assertFalse(row.toString().contains(player.toString()), "the imported player's raw UUID is never written to the audit log");
    }

    @Test
    void aRetemplateOfImportedBansRecordsOneImportRetemplateEntry() {
        this.bed.givenTemplate("cheating", "network", null, step("P7D", 100));
        PunishmentEntity imported = new PunishmentEntity("imp-1", VanillaImportService.SYSTEM_IMPORT_SOURCE, OWNER, "network", true, 1L,
                null, new HashMap<>(Map.of("reason", "Hacking")));
        this.bed.punishments.repository().save(imported);
        this.bed.profiles.repository().save(new PunishmentProfileEntity(OWNER, new HashMap<>(Map.of("network", imported)),
                new ArrayList<>(), new HashMap<>()));
        ImportedPunishmentService retemplating = new ImportedPunishmentService(
                this.bed.punishments.repository(), this.bed.profiles.repository(),
                new ImportTemplateResolver(this.bed.templates.repository(), this.bed.eloBed.sections), this.bed.eloBed.events,
                this.bed.profileCache, this.bed.cases.timeline, this.bed.eloBed.clock, this.bed.audit);

        ImportedPunishmentDTO result = retemplating.retemplate(new ImportedPunishmentDTO.RetemplateRequest("cheating", "Hacking", null), ACTOR);

        assertInstanceOf(ImportedPunishmentDTO.RetemplateResponse.class, result);
        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry");
        StaffAuditEntry row = this.bed.auditStore.rows().getLast();
        assertEquals("import.retemplate", row.action());
        assertEquals("NONE", row.targetKind());
        assertEquals(ACTOR.oid(), row.actorOid());
        assertNoBanIdOrIp(row, "imp-1");
    }

    @Test
    void anAdminRemovingADiscordLinkRecordsOneDiscordLinkRemoveEntryWithoutTheDiscordId() {
        DiscordLinkTestBed discord = new DiscordLinkTestBed(new RecordingEventPublisher(), new TestClock());
        DiscordLinkEntity link = discord.givenLink(DiscordLinkKind.PLAYER, OWNER, "111111111111111111");
        Actor admin = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false);

        assertInstanceOf(DiscordLinkDTO.RemovedResponse.class, discord.service.adminRemove(link.getId(), admin));

        assertEquals(1, discord.auditStore.rows().size(), "exactly one audit entry");
        StaffAuditEntry row = discord.auditStore.rows().getLast();
        assertEquals("discord-link.remove", row.action());
        assertEquals("NONE", row.targetKind());
        assertEquals(admin.oid(), row.actorOid());
        assertFalse(row.toString().contains("111111111111111111"), "the Discord user id is never written to the audit log");
    }

    /** An entry names its target by a short id, an owner hash, a key or nothing: never a Ban ID or an IP address. */
    private static void assertNoBanIdOrIp(StaffAuditEntry row, String banId) {
        String text = row.toString();
        assertFalse(text.contains(banId), "the Ban ID is not in the entry");
        assertFalse(IPV4.matcher(text).find(), "no IP address in the entry");
    }
}
