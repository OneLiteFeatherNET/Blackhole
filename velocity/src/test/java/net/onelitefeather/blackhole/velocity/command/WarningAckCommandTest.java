package net.onelitefeather.blackhole.velocity.command;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.FakeWarningBackend;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import net.onelitefeather.blackhole.velocity.enforcement.WarningHandler;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /blackhole warning ack <id>} acknowledges only for the owner of the warning. */
class WarningAckCommandTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000a1ec");
    private static final UUID SAM = UUID.fromString("00000000-0000-4000-8000-0000000005a1");
    private static final String WARNING_ID = "7K3M9QXA";

    private final FakeWarningBackend backend = new FakeWarningBackend();
    private final WarningAckCommand command = new WarningAckCommand(new WarningHandler(this.backend.gateway(), Runnable::run));
    private final String alexOwner = UUIDConverter.convertToSHA(ALEX);
    private final RecordingPlayer alex = new RecordingPlayer(ALEX, "Alex");
    private final RecordingPlayer sam = new RecordingPlayer(SAM, "Sam");

    @Test
    void theOwnerAcknowledgesTheirOwnWarningWithTheirOwnerHash() {
        this.backend.warnings.put(WARNING_ID, FakeWarningBackend.warning(WARNING_ID, this.alexOwner, "Spam in chat"));

        this.command.handle(this.alex.player(), WARNING_ID);

        assertEquals(List.of(WARNING_ID + "|" + this.alexOwner), this.backend.acknowledged);
        assertTrue(this.alex.texts().getFirst().contains("acknowledged"), this.alex.texts().toString());
    }

    @Test
    void anotherPlayerPostsNothingAndIsRefused() {
        this.backend.warnings.put(WARNING_ID, FakeWarningBackend.warning(WARNING_ID, this.alexOwner, "Spam in chat"));

        this.command.handle(this.sam.player(), WARNING_ID);

        assertTrue(this.backend.acknowledged.isEmpty(), "nothing was posted for a warning of another player");
        assertTrue(this.sam.texts().getFirst().contains("not yours"), this.sam.texts().toString());
    }

    @Test
    void anUnknownWarningPostsNothing() {
        this.command.handle(this.alex.player(), "NOPE0000");

        assertTrue(this.backend.acknowledged.isEmpty(), "an unknown warning is not acknowledged");
        assertTrue(this.alex.texts().getFirst().contains("No such warning"), this.alex.texts().toString());
    }

    @Test
    void aFailedReadPostsNothingAndSaysSo() {
        this.backend.warningFailure = new ApiException(500, "backend down");

        this.command.handle(this.alex.player(), WARNING_ID);

        assertTrue(this.backend.acknowledged.isEmpty(), "nothing was posted when the owner could not be checked");
        assertTrue(this.alex.texts().getFirst().contains("could not be acknowledged"), this.alex.texts().toString());
    }
}
