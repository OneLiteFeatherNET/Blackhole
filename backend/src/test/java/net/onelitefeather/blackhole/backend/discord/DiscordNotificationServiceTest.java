package net.onelitefeather.blackhole.backend.discord;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordNotificationDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.report.ReportCategory;
import net.onelitefeather.blackhole.backend.report.ReportEntity;
import net.onelitefeather.blackhole.backend.report.ReportRepository;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordNotificationServiceTest {

    private static final String HASH = "0123456789abcdef".repeat(8);

    private final InMemoryRepository<ReportRepository, ReportEntity, UUID> reports =
            InMemoryRepository.<ReportRepository, ReportEntity, UUID>of(ReportRepository.class, ReportEntity::getIdentifier).build();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final DiscordNotificationService service = new DiscordNotificationService(this.reports.repository(), this.appeals.repository());

    private UUID givenReport(String description, Map<String, Object> metaData) {
        UUID id = UUID.randomUUID();
        this.reports.given(new ReportEntity("reporter", HASH, ReportCategory.CHEATING, description, new ArrayList<>(),
                ReportStatus.OPEN, 10L, 20L, null, null, metaData), id);
        return id;
    }

    private UUID givenAppeal(String statement, Map<String, Object> appealMeta, Map<String, Object> punishmentMeta) {
        UUID id = UUID.randomUUID();
        PunishmentEntity punishment = new PunishmentEntity("punishment-1", UUID.randomUUID(), HASH, "chat", false, 1L, null, punishmentMeta);
        this.appeals.given(new AppealEntity(punishment, HASH, statement, AppealStatus.ELIGIBLE_PENDING_REVIEW, new HashMap<>(), 10L, 20L, appealMeta), id);
        return id;
    }

    @Test
    void aReportShowsTheRecordedNameAndAShortPseudonymNeverTheFullHash() {
        UUID id = givenReport("kill aura", Map.of("reportedName", "Griefer_01"));

        DiscordNotificationDTO.Report report = assertInstanceOf(DiscordNotificationDTO.Report.class, this.service.report(id));

        assertEquals("Griefer_01", report.playerName(), "the recorded name");
        assertEquals("01234567", report.playerRef(), "first 8 characters only");
        assertEquals("CHEATING", report.category(), "category");
        assertEquals("OPEN", report.status(), "status");
        assertFalse(report.toString().contains(HASH), "the full hash is not exposed");
    }

    @Test
    void aReportWithoutARecordedNameHasNoNameAndAnInvalidNameIsIgnored() {
        assertNull(assertInstanceOf(DiscordNotificationDTO.Report.class, this.service.report(givenReport("x", Map.of()))).playerName(), "no name recorded");
        UUID odd = givenReport("x", Map.of("reportedName", "not a valid <name>"));

        assertNull(assertInstanceOf(DiscordNotificationDTO.Report.class, this.service.report(odd)).playerName(), "only a Minecraft name is accepted");
    }

    @Test
    void aLongDescriptionIsShortenedAndABlankOneIsAbsent() {
        UUID longOne = givenReport("a".repeat(500), Map.of());
        UUID blank = givenReport("   ", Map.of());

        String shortened = assertInstanceOf(DiscordNotificationDTO.Report.class, this.service.report(longOne)).description();
        assertEquals(300, shortened.length(), "limited");
        assertTrue(shortened.endsWith("…"), "marked as cut");
        assertNull(assertInstanceOf(DiscordNotificationDTO.Report.class, this.service.report(blank)).description(), "blank");
    }

    @Test
    void anUnknownReportIsNotFound() {
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(DiscordNotificationDTO.Error.class, this.service.report(UUID.randomUUID())).kind(), "404");
    }

    @Test
    void anAppealCarriesTheSectionAndThePunishmentIdentifierForTheCaseLink() {
        UUID id = givenAppeal("please", Map.of(), Map.of());

        DiscordNotificationDTO.Appeal appeal = assertInstanceOf(DiscordNotificationDTO.Appeal.class, this.service.appeal(id));

        assertEquals("punishment-1", appeal.punishmentIdentifier(), "case link target");
        assertEquals("chat", appeal.section(), "section");
        assertEquals("ELIGIBLE_PENDING_REVIEW", appeal.status(), "status");
        assertEquals("please", appeal.statement(), "statement");
        assertNull(appeal.playerName(), "no name recorded");
    }

    @Test
    void anAppealTakesTheNameFromItsOwnMetadataBeforeThePunishments() {
        UUID own = givenAppeal("s", Map.of("appellantName", "Alice"), Map.of("targetName", "Bob"));
        UUID fallback = givenAppeal("s", Map.of(), Map.of("targetName", "Bob"));

        assertEquals("Alice", assertInstanceOf(DiscordNotificationDTO.Appeal.class, this.service.appeal(own)).playerName(), "own metadata first");
        assertEquals("Bob", assertInstanceOf(DiscordNotificationDTO.Appeal.class, this.service.appeal(fallback)).playerName(), "then the punishment's");
    }

    @Test
    void anUnknownAppealIsNotFound() {
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(DiscordNotificationDTO.Error.class, this.service.appeal(UUID.randomUUID())).kind(), "404");
    }
}
