package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.report.ReportEvidence;
import net.onelitefeather.blackhole.velocity.report.ReportService;
import net.onelitefeather.blackhole.velocity.report.ReportService.Outcome;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions behind /report, with the backend replaced by a stub and the chat buffer by a fresh one. */
class ReportServiceTest {

    private static final UUID REPORTER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID REPORTED = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String REPORTER_HASH = UUIDConverter.convertToSHA(REPORTER);
    private static final String REPORTED_HASH = UUIDConverter.convertToSHA(REPORTED);

    private final StubGateway backend = new StubGateway();
    private final ChatCaptureConfig config = new ChatCaptureConfig();
    private final ChatBuffer buffer;
    private final ReportService service;

    ReportServiceTest() {
        this.config.setEnabled(true);
        this.buffer = new ChatBuffer(new MutableClock(), config);
        ReportEvidence evidence = new ReportEvidence(new ChatSnapshotBuilder(buffer, config), backend, config, () -> "corr-1");
        this.service = new ReportService(backend, evidence);
    }

    @Test
    void aReportCarriesTheHashesTheCategoryAndTheDescription() {
        Outcome outcome = service.submit(REPORTER, REPORTED, false, ReportCategory.CHEATING, "  flying  ");

        assertInstanceOf(Outcome.Submitted.class, outcome);
        assertEquals(List.of(REPORTER_HASH + "|" + REPORTED_HASH + "|CHEATING|flying|[]"), backend.reports,
                "the backend must get hashes only and the stripped description");
    }

    @Test
    void theReportedPlayersRecentChatIsAttachedAndReferenced() {
        buffer.record(REPORTED_HASH, "lobby", "bad words");

        Outcome outcome = service.submit(REPORTER, REPORTED, false, ReportCategory.CHAT_ABUSE, null);

        Outcome.Submitted submitted = assertInstanceOf(Outcome.Submitted.class, outcome);
        assertEquals(ReportEvidence.Result.Status.ATTACHED, submitted.evidence().status());
        assertEquals(1, submitted.evidence().lines());
        assertEquals(List.of(REPORTED_HASH), backend.reportEvidenceOwners, "the evidence belongs to the reported player");
        assertEquals(List.of("report-evidence", "report"), backend.calls, "the evidence must exist before the report references it");
        assertEquals(List.of(REPORTER_HASH + "|" + REPORTED_HASH + "|CHAT_ABUSE|null|[" + backend.reportEvidenceId + "]"), backend.reports);
    }

    @Test
    void withoutBufferedChatNoEvidenceIsPosted() {
        Outcome.Submitted submitted = assertInstanceOf(Outcome.Submitted.class,
                service.submit(REPORTER, REPORTED, false, ReportCategory.GRIEFING, null));

        assertEquals(ReportEvidence.Result.Status.NONE, submitted.evidence().status());
        assertTrue(backend.reportEvidenceOwners.isEmpty());
    }

    @Test
    void whileCaptureIsDisabledNoChatIsSent() {
        config.setEnabled(false);
        buffer.record(REPORTED_HASH, "lobby", "bad words");
        ReportService disabled = new ReportService(backend,
                new ReportEvidence(new ChatSnapshotBuilder(buffer, config), backend, config, () -> "corr-1"));

        disabled.submit(REPORTER, REPORTED, false, ReportCategory.CHAT_ABUSE, null);

        assertTrue(backend.reportEvidenceOwners.isEmpty(), "the default configuration must never send chat");
    }

    @Test
    void aFailedEvidenceUploadDoesNotStopTheReport() {
        buffer.record(REPORTED_HASH, "lobby", "bad words");
        backend.reportEvidenceResult = CompletableFuture.failedFuture(new IllegalStateException("down"));

        Outcome.Submitted submitted = assertInstanceOf(Outcome.Submitted.class,
                service.submit(REPORTER, REPORTED, false, ReportCategory.CHAT_ABUSE, null));

        assertEquals(ReportEvidence.Result.Status.FAILED, submitted.evidence().status());
        assertEquals(1, backend.reports.size(), "the report must still be filed");
        assertTrue(backend.reports.getFirst().endsWith("|[]"), "no evidence may be referenced");
    }

    @Test
    void reportingYourselfIsRefusedWithoutAnyBackendCall() {
        assertInstanceOf(Outcome.SelfReport.class, service.submit(REPORTER, REPORTER, false, ReportCategory.CHEATING, null));

        assertTrue(backend.calls.isEmpty());
    }

    @Test
    void aProtectedPlayerCannotBeReported() {
        assertInstanceOf(Outcome.Protected.class, service.submit(REPORTER, REPORTED, true, ReportCategory.CHEATING, null));

        assertTrue(backend.calls.isEmpty());
    }

    @Test
    void theReasonOtherNeedsADescription() {
        assertInstanceOf(Outcome.DetailsRequired.class, service.submit(REPORTER, REPORTED, false, ReportCategory.OTHER, "   "));

        assertTrue(backend.calls.isEmpty());
    }

    @Test
    void aTooLongDescriptionIsRefusedBeforeTheBackendSeesIt() {
        Outcome outcome = service.submit(REPORTER, REPORTED, false, ReportCategory.OTHER, "x".repeat(ReportService.MAX_DETAILS + 1));

        assertEquals(new Outcome.DetailsTooLong(ReportService.MAX_DETAILS), outcome);
        assertTrue(backend.calls.isEmpty());
    }

    @Test
    void theBackendsRateLimitIsPassedOn() {
        backend.reportFailure = new ApiException(429, "too many", null, null);

        assertInstanceOf(Outcome.RateLimited.class, service.submit(REPORTER, REPORTED, false, ReportCategory.CHEATING, null));
    }

    @Test
    void anyOtherBackendFailureIsAFailure() {
        backend.reportFailure = new ApiException(500, "boom", null, null);

        assertInstanceOf(Outcome.Failed.class, service.submit(REPORTER, REPORTED, false, ReportCategory.CHEATING, null));
    }

    @Test
    void thePluginAddsNoCooldownOfItsOwn() {
        service.submit(REPORTER, REPORTED, false, ReportCategory.CHEATING, null);
        service.submit(REPORTER, REPORTED, false, ReportCategory.CHEATING, null);

        assertEquals(2, backend.reports.size(), "only the backend's limiter may refuse a report");
    }

    @Test
    void reportEvidenceIsMarkedWithTheReportCorrelationPrefix() {
        buffer.record(REPORTED_HASH, "lobby", "bad words");
        ReportService real = new ReportService(backend, ReportEvidence.create(new ChatSnapshotBuilder(buffer, config), backend, config));

        real.submit(REPORTER, REPORTED, false, ReportCategory.CHAT_ABUSE, null);

        assertTrue(backend.reportEvidenceCorrelationIds.getFirst().startsWith("report:"),
                "the backend keeps evidence with this prefix until the report is resolved");
    }
}
