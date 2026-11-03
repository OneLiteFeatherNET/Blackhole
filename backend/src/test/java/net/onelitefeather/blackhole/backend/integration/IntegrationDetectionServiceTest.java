package net.onelitefeather.blackhole.backend.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.security.authentication.Authentication;
import net.onelitefeather.blackhole.backend.imports.UUIDHasher;
import net.onelitefeather.blackhole.backend.integration.dto.DetectionReason;
import net.onelitefeather.blackhole.backend.integration.dto.DetectionStatus;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDetectionDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.CLIENT;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.batch;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.item;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.vulcan;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.with;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.withPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationDetectionServiceTest {

    private final IntegrationServiceTest.FakeStore registrations = new IntegrationServiceTest.FakeStore();
    private final FakeIntegrationDetectionStore detections = new FakeIntegrationDetectionStore();
    private final TestClock clock = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final IntegrationDetectionService service = new IntegrationDetectionService(registrations, detections,
            new DetectionValidator(new SchemaRegistry()), clock, meters);
    private final SnapshotLogAppender appender = new SnapshotLogAppender();
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void registerVulcanAndCaptureLogs() {
        this.registrations.insert(vulcan(true), UUID.randomUUID(), this.clock.millis());
        this.logger = (Logger) LoggerFactory.getLogger(IntegrationDetectionService.class);
        this.previousLevel = this.logger.getLevel();
        this.logger.setLevel(Level.INFO);
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    /** The logger is shared by the whole JVM: the appender and the level are put back. */
    @AfterEach
    void detachLogs() {
        this.logger.detachAppender(this.appender);
        this.logger.setLevel(this.previousLevel);
    }

    private static Authentication caller(String azp) {
        return Authentication.build("integration", List.of("INTEGRATION"), Map.of("azp", azp));
    }

    private IntegrationDetectionDTO ingest(String body) {
        return this.service.ingest(body, caller(CLIENT));
    }

    private static List<IntegrationDetectionDTO.ItemResult> results(IntegrationDetectionDTO answer) {
        return assertInstanceOf(IntegrationDetectionDTO.Response.class, answer).results();
    }

    @Test
    void aValidDetectionIsAcceptedAndARetryOfItIsDuplicateAndChangesNothing() {
        Map<String, Object> item = item();

        assertEquals(DetectionStatus.ACCEPTED, results(ingest(batch(List.of(item)))).get(0).status());
        assertEquals(DetectionStatus.DUPLICATE, results(ingest(batch(List.of(item)))).get(0).status());
        assertEquals(1, this.detections.stored().size(), "the duplicate wrote no second row");
    }

    @Test
    void aCallerWithoutAnEnabledRegistrationIsForbiddenAndNothingIsStored() {
        IntegrationDetectionDTO answer = this.service.ingest(batch(List.of(item())), caller("00000000-0000-4000-8000-0000000000ee"));

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(IntegrationDetectionDTO.Error.class, answer).kind());
        assertTrue(this.detections.stored().isEmpty());
    }

    @Test
    void aDisabledRegistrationIsForbidden() {
        IntegrationServiceTest.FakeStore disabledStore = new IntegrationServiceTest.FakeStore();
        disabledStore.insert(vulcan(false), UUID.randomUUID(), this.clock.millis());
        IntegrationDetectionService service = new IntegrationDetectionService(disabledStore, this.detections,
                new DetectionValidator(new SchemaRegistry()), this.clock, this.meters);

        IntegrationDetectionDTO answer = service.ingest(batch(List.of(item())), caller(CLIENT));

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(IntegrationDetectionDTO.Error.class, answer).kind());
    }

    @Test
    void aCallWithoutAnAuthorizedPartyIsForbidden() {
        Authentication withoutAzp = Authentication.build("integration", List.of("INTEGRATION"), Map.of());

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(IntegrationDetectionDTO.Error.class,
                this.service.ingest(batch(List.of(item())), withoutAzp)).kind());
    }

    @Test
    void aBatchOfFiftyOneItemsIsRefusedBeforeAnyItemIsStored() {
        List<Map<String, Object>> items = java.util.stream.IntStream.range(0, 51).mapToObj(i -> item()).toList();

        IntegrationDetectionDTO answer = ingest(batch(items));

        assertEquals(ErrorKind.PAYLOAD_TOO_LARGE, assertInstanceOf(IntegrationDetectionDTO.Error.class, answer).kind());
        assertTrue(this.detections.stored().isEmpty());
    }

    @Test
    void aBatchOfFiftyItemsIsWithinTheCap() {
        List<Map<String, Object>> items = java.util.stream.IntStream.range(0, 50).mapToObj(i -> item()).toList();

        assertEquals(50, results(ingest(batch(items))).size());
    }

    @Test
    void aBodyLargerThan64KiBIsRefusedBeforeItIsParsed() {
        String body = "{\"detections\": [\"" + "x".repeat(IntegrationDetectionService.MAX_BODY_BYTES) + "\"]}";

        assertEquals(ErrorKind.PAYLOAD_TOO_LARGE, assertInstanceOf(IntegrationDetectionDTO.Error.class, ingest(body)).kind());
    }

    @Test
    void aBodyThatIsNotTheBatchShapeIsAValidationError() {
        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(IntegrationDetectionDTO.Error.class, ingest("[1, 2]")).kind());
        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(IntegrationDetectionDTO.Error.class, ingest("{\"items\": []}")).kind());
        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(IntegrationDetectionDTO.Error.class, ingest("not json")).kind());
    }

    @Test
    void theAnswerListsEveryItemInRequestOrderAndStoresOnlyTheValidOnes() {
        Map<String, Object> valid = item();
        Map<String, Object> invalid = with(item(), "owner", "not-a-uuid");
        Map<String, Object> duplicate = valid;

        List<IntegrationDetectionDTO.ItemResult> results = results(ingest(batch(List.of(valid, invalid, duplicate))));

        assertEquals(List.of(DetectionStatus.ACCEPTED, DetectionStatus.REJECTED, DetectionStatus.DUPLICATE),
                results.stream().map(IntegrationDetectionDTO.ItemResult::status).toList());
        assertEquals(DetectionReason.SCHEMA_INVALID, results.get(1).reason());
        assertEquals(List.of(0, 1, 2), results.stream().map(IntegrationDetectionDTO.ItemResult::index).toList());
        assertEquals(1, this.detections.stored().size());
    }

    @Test
    void aRetriedBatchStoresOnlyItsNewItems() {
        Map<String, Object> first = item();
        Map<String, Object> second = item();
        ingest(batch(List.of(first)));

        List<IntegrationDetectionDTO.ItemResult> retry = results(ingest(batch(List.of(first, second))));

        assertEquals(List.of(DetectionStatus.DUPLICATE, DetectionStatus.ACCEPTED),
                retry.stream().map(IntegrationDetectionDTO.ItemResult::status).toList());
        assertEquals(2, this.detections.stored().size());
    }

    @Test
    void theOwnerIsStoredAsItsHashAndTheRawUuidIsAbsent() {
        Map<String, Object> item = item();
        String rawOwner = (String) item.get("owner");

        ingest(batch(List.of(item)));

        IntegrationDetectionStore.NewDetection stored = this.detections.stored().get(0);
        assertEquals(128, stored.ownerHash().length());
        assertEquals(UUIDHasher.hash(UUID.fromString(rawOwner)), stored.ownerHash());
        assertFalse(stored.ownerHash().contains(rawOwner), "the raw UUID is not part of the stored row");
        assertFalse(stored.payloadJson().contains(rawOwner));
    }

    @Test
    void thePayloadWithAUuidIsNotStored() {
        ingest(batch(List.of(withPayload(item(), "detail", "player " + UUID.randomUUID()))));

        assertTrue(this.detections.stored().isEmpty());
    }

    @Test
    void theAcceptedRowCarriesTheReceiptTimeFromTheServerClock() {
        ingest(batch(List.of(item())));

        assertEquals(this.clock.millis(), this.detections.stored().get(0).receivedAt());
    }

    @Test
    void outcomesAreCountedByStatusAndReason() {
        ingest(batch(List.of(item(), with(item(), "owner", "bad"))));

        assertEquals(1.0, this.meters.counter(IntegrationDetectionService.METER, "outcome", "ACCEPTED", "reason", "none").count());
        assertEquals(1.0, this.meters.counter(IntegrationDetectionService.METER, "outcome", "REJECTED", "reason", "SCHEMA_INVALID").count());
    }

    @Test
    void theLogsCarryCountsAndNeverThePayloadSummaryOrOwner() {
        Map<String, Object> item = item();
        String owner = (String) item.get("owner");
        String detail = "Attack angle delta above threshold";

        ingest(batch(List.of(item, with(item(), "owner", owner))));

        List<ILoggingEvent> lines = this.appender.snapshot();
        assertFalse(lines.isEmpty(), "the batch is logged once");
        for (ILoggingEvent line : lines) {
            String text = line.getFormattedMessage();
            assertFalse(text.contains(owner), "no raw owner in: " + text);
            assertFalse(text.contains(detail), "no detail in: " + text);
            assertFalse(text.contains("KillAura"), "no check name in: " + text);
            assertFalse(text.contains("flag"), "no summary in: " + text);
        }
    }
}
