package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.FakeWarningBackend;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On login the owner's pending warnings are shown, and a failed read never refuses the login. */
class PendingWarningsOnLoginTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000a1ec");

    private final FakeWarningBackend backend = new FakeWarningBackend();
    private final WarningHandler handler = new WarningHandler(this.backend.gateway(), Runnable::run);
    private final RecordingPlayer alex = new RecordingPlayer(ALEX, "Alex");
    private final String alexOwner = UUIDConverter.convertToSHA(ALEX);

    @Test
    void everyPendingWarningIsShownOnLogin() {
        this.backend.pending = List.of(
                FakeWarningBackend.warning("W1", this.alexOwner, "Spam in chat"),
                FakeWarningBackend.warning("W2", this.alexOwner, "Advertising"));

        this.handler.deliverPending(this.alex.player());

        assertEquals(2, this.alex.texts().size(), this.alex.texts().toString());
        assertTrue(this.alex.texts().get(0).contains("Spam in chat"), this.alex.texts().toString());
        assertTrue(this.alex.texts().get(1).contains("Advertising"), this.alex.texts().toString());
    }

    @Test
    void thePendingReadAsksForTheOwnerHashOfThePlayer() {
        this.handler.deliverPending(this.alex.player());

        assertEquals(List.of(this.alexOwner), this.backend.pendingOwners);
    }

    @Test
    void aPendingWarningIsShownAgainOnTheNextLogin() {
        this.backend.pending = List.of(FakeWarningBackend.warning("W1", this.alexOwner, "Spam in chat"));

        this.handler.deliverPending(this.alex.player());
        this.handler.deliverPending(this.alex.player());

        assertEquals(2, this.alex.texts().size(), "each login shows what is still pending: " + this.alex.texts());
    }

    @Test
    void aFailedPendingReadNeitherRefusesTheLoginNorShowsAnything() {
        this.backend.pendingFailure = new ApiException(500, "backend down");

        assertDoesNotThrow(() -> this.handler.deliverPending(this.alex.player()));

        assertTrue(this.alex.texts().isEmpty(), "nothing is shown when the read fails");
    }
}
