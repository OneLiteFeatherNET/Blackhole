package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.FakeWarningBackend;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A warning event shows the warning to the owner when they are connected to this proxy, and to nobody else. */
class WarningDeliveryTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000a1ec");
    private static final UUID SAM = UUID.fromString("00000000-0000-4000-8000-0000000005a1");
    private static final String WARNING_ID = "7K3M9QXA";

    private final FakeWarningBackend backend = new FakeWarningBackend();
    private final WarningHandler handler = new WarningHandler(backend.gateway(), Runnable::run);
    private final RecordingPlayer alex = new RecordingPlayer(ALEX, "Alex");
    private final String alexOwner = UUIDConverter.convertToSHA(ALEX);

    @Test
    void aWarningForAConnectedOwnerShowsItsReasonWithTheAcknowledgeLine() {
        this.backend.warnings.put(WARNING_ID, FakeWarningBackend.warning(WARNING_ID, this.alexOwner, "Spam in chat"));

        this.handler.warned(new WarningMessage(WARNING_ID, this.alexOwner), List.of(this.alex.player()));

        assertEquals(1, this.alex.texts().size(), this.alex.texts().toString());
        assertTrue(this.alex.texts().getFirst().contains("Spam in chat"), this.alex.texts().toString());
    }

    @Test
    void aWarningStaysPendingUntilThePlayerAcknowledgesIt() {
        this.backend.warnings.put(WARNING_ID, FakeWarningBackend.warning(WARNING_ID, this.alexOwner, "Spam in chat"));

        this.handler.warned(new WarningMessage(WARNING_ID, this.alexOwner), List.of(this.alex.player()));

        assertTrue(this.backend.acknowledged.isEmpty(), "showing a warning is not confirming it");
    }

    @Test
    void anOwnerNotConnectedHereDoesNothing() {
        RecordingPlayer sam = new RecordingPlayer(SAM, "Sam");

        this.handler.warned(new WarningMessage(WARNING_ID, this.alexOwner), List.of(sam.player()));

        assertTrue(this.backend.warningReads.isEmpty(), "the text is not read for an owner who is not here");
        assertTrue(sam.texts().isEmpty(), "nobody else is told");
        assertTrue(this.alex.texts().isEmpty());
    }

    @Test
    void aFailedReadOfTheTextShowsTheDefaultEnglishText() {
        this.backend.warningFailure = new ApiException(500, "backend down");

        assertDoesNotThrow(() -> this.handler.warned(new WarningMessage(WARNING_ID, this.alexOwner), List.of(this.alex.player())));

        assertEquals(1, this.alex.texts().size(), "the player is still told");
        assertTrue(this.alex.texts().getFirst().contains("You received a warning"), this.alex.texts().toString());
        assertFalse(this.alex.texts().getFirst().contains("null"), "no reason is shown as null");
    }
}
