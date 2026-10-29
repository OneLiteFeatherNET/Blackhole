package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidence;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static net.onelitefeather.blackhole.velocity.punish.StubGateway.template;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staff punish flow with chat capture: evidence is posted first, the punishment claims it by
 * correlation id and nothing about evidence can stop the punishment.
 */
class PunishServiceEvidenceTest {

    private static final UUID STAFF = UUID.randomUUID();

    private final StubGateway backend = new StubGateway();
    private final ChatCaptureConfig config = new ChatCaptureConfig();
    private final ChatBuffer buffer;
    private PunishService service;

    PunishServiceEvidenceTest() {
        this.config.setEnabled(true);
        this.buffer = new ChatBuffer(new MutableClock(), config);
        this.backend.templates = List.of(template("hacking", "network"));
        this.service = serviceWithCorrelationId("corr-1");
    }

    private PunishService serviceWithCorrelationId(String id) {
        ChatEvidence evidence = new ChatEvidence(new ChatSnapshotBuilder(buffer, config), backend, config, () -> id);
        return new PunishService(backend, standardRegistry(), evidence);
    }

    private PunishService.ApplyOutcome.Applied ban() {
        return assertInstanceOf(PunishService.ApplyOutcome.Applied.class,
                service.apply(OWNER, null, "hacking", STAFF, PunishService.BAN_KINDS, false));
    }

    /** A pending upload that gives up when asked for its result, as a timeout does. */
    private static CompletableFuture<Void> timingOut() {
        return new CompletableFuture<>() {
            @Override
            public Void get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException();
            }
        };
    }

    @Test
    void bufferedChatIsPostedBeforeThePunishmentWhichCarriesTheSameCorrelationId() {
        buffer.record(OWNER, "lobby", "bad words");

        var applied = ban();

        assertEquals(List.of("evidence", "apply"), backend.calls, "evidence must be stored before the punishment claims it");
        assertEquals(List.of("corr-1"), backend.appliedCorrelationIds, "the punishment must claim the evidence it follows");
        assertEquals(List.of(OWNER + "/corr-1/velocity"), backend.postedEvidence, "evidence is filed for the target under source system velocity");
        assertEquals(ChatEvidence.Result.Status.ATTACHED, applied.evidence().status());
        assertEquals(1, applied.evidence().lines());
    }

    @Test
    void aFreshCorrelationIdIsUsedForEveryPunishment() {
        buffer.record(OWNER, "lobby", "bad words");
        int[] counter = {0};
        ChatEvidence evidence = new ChatEvidence(new ChatSnapshotBuilder(buffer, config), backend, config, () -> "corr-" + ++counter[0]);
        service = new PunishService(backend, standardRegistry(), evidence);

        ban();
        ban();

        assertEquals(List.of("corr-1", "corr-2"), backend.appliedCorrelationIds);
    }

    @Test
    void withoutBufferedChatNothingIsPostedAndThePunishmentCarriesNoCorrelationId() {
        var applied = ban();

        assertEquals(List.of("apply"), backend.calls);
        assertNull(backend.appliedCorrelationIds.getFirst(), "there is no evidence to claim");
        assertEquals(ChatEvidence.Result.Status.NONE, applied.evidence().status());
    }

    @Test
    void whileCaptureIsDisabledNothingIsPosted() {
        config.setEnabled(false);
        buffer.record(OWNER, "lobby", "bad words");
        service = serviceWithCorrelationId("corr-1");

        var applied = ban();

        assertTrue(backend.postedEvidence.isEmpty(), "the default configuration must never send chat");
        assertEquals(ChatEvidence.Result.Status.NONE, applied.evidence().status());
    }

    @Test
    void aRefusedEvidenceUploadStillAppliesThePunishmentWithoutACorrelationId() {
        buffer.record(OWNER, "lobby", "bad words");
        backend.evidenceResult = CompletableFuture.failedFuture(new ApiException(422, "refused"));

        var applied = ban();

        assertEquals(List.of("hacking"), backend.applied, "the punishment must not depend on the evidence");
        assertNull(backend.appliedCorrelationIds.getFirst(), "evidence that was not stored cannot be claimed");
        assertEquals(ChatEvidence.Result.Status.FAILED, applied.evidence().status(), "staff are warned");
    }

    @Test
    void anEvidenceUploadThatTimesOutStillAppliesThePunishment() {
        buffer.record(OWNER, "lobby", "bad words");
        backend.evidenceResult = timingOut();

        var applied = ban();

        assertEquals(List.of("hacking"), backend.applied, "a slow evidence service must not hold the punishment back");
        assertEquals(ChatEvidence.Result.Status.FAILED, applied.evidence().status());
    }

    @Test
    void aGatewayThatThrowsInsteadOfFailingItsFutureStillAppliesThePunishment() {
        buffer.record(OWNER, "lobby", "bad words");
        backend.evidenceFailure = new IllegalStateException("client broke");

        var applied = ban();

        assertEquals(List.of("hacking"), backend.applied);
        assertEquals(ChatEvidence.Result.Status.FAILED, applied.evidence().status());
    }

    @Test
    void aFailedPunishmentAfterPostedEvidenceStillReportsTheFailure() {
        buffer.record(OWNER, "lobby", "bad words");
        backend.applyFailure = new ApiException(500, "down");

        var outcome = assertInstanceOf(PunishService.ApplyOutcome.Failed.class,
                service.apply(OWNER, null, "hacking", STAFF, PunishService.BAN_KINDS, false));

        assertEquals(500, outcome.status());
        assertEquals(List.of("evidence", "apply"), backend.calls, "the orphaned evidence is left to expire on the backend");
    }

    @Test
    void noEvidenceIsPostedForATemplateTheCommandMayNotUse() {
        buffer.record(OWNER, "lobby", "bad words");

        assertInstanceOf(PunishService.ApplyOutcome.WrongSection.class,
                service.apply(OWNER, null, "hacking", STAFF, PunishService.MUTE_KINDS, false));

        assertTrue(backend.postedEvidence.isEmpty(), "chat must only leave the buffer for a punishment that is really applied");
    }

    @Test
    void thePostedEvidenceCarriesAliasesInsteadOfOwnerHashes() {
        buffer.record("c".repeat(128), "lobby", "provocation");
        buffer.record(OWNER, "lobby", "bad words");

        ban();

        var speakers = backend.postedSnapshots.getFirst().lines().stream().map(entry -> entry.speaker()).toList();
        assertEquals(List.of("Spieler A", "Spieler B"), speakers, "speakers are aliased in order of appearance, the target included");
    }
}
