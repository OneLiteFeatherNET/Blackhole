package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordLinkCodeResponse;
import net.onelitefeather.blackhole.velocity.punish.DiscordLinkService.CodeOutcome;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DiscordLinkServiceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final StubGateway backend = new StubGateway();
    private final DiscordLinkService service = new DiscordLinkService(this.backend, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theBackendOnlySeesTheHashedOwnerNeverTheRawUuid() {
        this.service.issueCode(PLAYER);

        assertEquals(List.of(UUIDConverter.convertToSHA(PLAYER)), this.backend.discordOwners, "hashed owner");
        assertFalse(this.backend.discordOwners.getFirst().contains(PLAYER.toString()), "no raw UUID");
    }

    @Test
    void anIssuedCodeCarriesTheMinutesLeftRoundedUp() {
        this.backend.discordResponse = new DiscordLinkCodeResponse().code("ABCD-EFGH").expiresAt(NOW.plusSeconds(9 * 60 + 30).toEpochMilli());

        assertEquals(new CodeOutcome.Issued("ABCD-EFGH", 10), this.service.issueCode(PLAYER), "9.5 minutes shows as 10");
    }

    @Test
    void aCodeAboutToExpireStillShowsOneMinute() {
        this.backend.discordResponse = new DiscordLinkCodeResponse().code("ABCD-EFGH").expiresAt(NOW.toEpochMilli());

        assertEquals(new CodeOutcome.Issued("ABCD-EFGH", 1), this.service.issueCode(PLAYER), "never zero");
    }

    @Test
    void aBackendFailureIsAnOutcomeNotAnException() {
        this.backend.discordFailure = new ApiException(503, "down");

        assertEquals(new CodeOutcome.Failed(503), this.service.issueCode(PLAYER), "failed with the status");
    }
}
