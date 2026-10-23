package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkLookupResponse;
import net.onelitefeather.blackhole.velocity.punish.StaffLinkService.LinkOutcome;
import net.onelitefeather.blackhole.velocity.punish.StaffLinkService.WhoamiOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StaffLinkServiceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final StubGateway backend = new StubGateway();
    private final StaffLinkService service = new StaffLinkService(backend);

    @Test
    void aLiveCodeLinksAndNamesTheStaffMember() {
        backend.confirmResponse = new StaffLinkConfirmResponse().displayName("Alex Admin");

        LinkOutcome outcome = service.link("ABCD-EFGH", PLAYER);

        assertEquals(new LinkOutcome.Linked("Alex Admin"), outcome, "the display name comes from the backend");
    }

    @Test
    void aMissingDisplayNameStillLinks() {
        LinkOutcome outcome = service.link("ABCD-EFGH", PLAYER);

        assertEquals(new LinkOutcome.Linked(null), outcome, "the link exists even if the backend has no name");
    }

    @Test
    void theCodeAndTheExecutingPlayerAreSentAsTyped() {
        service.link("abcd-efgh", PLAYER);

        assertEquals(List.of(PLAYER + "/abcd-efgh"), backend.confirmed, "the backend normalises, the proxy must not alter the input");
    }

    @Test
    void theBackendsInvalidCodeAnswerIsOneUniformOutcome() {
        backend.confirmFailure = new ApiException(400, "INVALID_CODE");

        assertInstanceOf(LinkOutcome.InvalidCode.class, service.link("ABCD-EFGH", PLAYER), "wrong, expired and used look the same");
    }

    @Test
    void aConflictIsABackendFailure() {
        backend.confirmFailure = new ApiException(409, "conflict");

        assertEquals(new LinkOutcome.Failed(409), service.link("ABCD-EFGH", PLAYER), "only 400 means invalid code");
    }

    @Test
    void anUnreachableBackendFailsWithStatusZero() {
        backend.confirmFailure = new ApiException(new java.io.IOException("down"));

        assertEquals(new LinkOutcome.Failed(0), service.link("ABCD-EFGH", PLAYER), "no status without a response");
    }

    @Test
    void aLinkedAccountIsReportedWithTheName() {
        backend.lookupResponse = new StaffLinkLookupResponse().linked(true).displayName("Alex Admin");

        assertEquals(new WhoamiOutcome.Linked("Alex Admin"), service.whoami(PLAYER), "linked status with name");
    }

    @Test
    void anUnlinkedAccountIsReported() {
        assertInstanceOf(WhoamiOutcome.NotLinked.class, service.whoami(PLAYER), "not linked");
    }

    @Test
    void aFailingLookupIsReported() {
        backend.lookupFailure = new ApiException(500, "boom");

        assertEquals(new WhoamiOutcome.Failed(500), service.whoami(PLAYER), "the status is passed on");
    }
}
