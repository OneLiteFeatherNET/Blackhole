package net.onelitefeather.blackhole.backend.playerresolver.service;

import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.playerresolver.PlayerResolveCache;
import net.onelitefeather.blackhole.backend.playerresolver.PlayerResolver;
import net.onelitefeather.blackhole.backend.playerresolver.ResolvedPlayer;
import net.onelitefeather.blackhole.backend.playerresolver.dto.PlayerResolveDTO;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlayerLookupServiceTest {

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** SHA-512 of the UUID's canonical string, computed independently of the production code. */
    private static final String STEVE_OWNER =
            "25f439e181b50a87549b6118bf7ff46837aa07d65517ed2f86e22c2987ceae11"
                    + "7c5ae27b92163d93a789ec74792f3050cf8bf61fe575d31588071eb0127245b5";

    private static PlayerLookupService serviceResolving(String name, ResolvedPlayer player) {
        PlayerResolver resolver = candidate -> candidate.equals(name) ? Optional.of(player) : Optional.empty();
        PlayerResolveCache cache = new PlayerResolveCache(new InMemorySharedCache(), Duration.ofMinutes(5));
        return new PlayerLookupService(new PlayerResolverService(List.of(resolver), cache));
    }

    @Test
    void lookupCarriesTheOwnerHashProxiesComputeForTheUuid() {
        PlayerLookupService service = serviceResolving("Steve", new ResolvedPlayer(STEVE, "Steve", "otis"));

        PlayerResolveDTO result = service.resolve("Steve");

        PlayerResolveDTO.Response response = assertInstanceOf(PlayerResolveDTO.Response.class, result);
        assertEquals(STEVE_OWNER, response.owner(), "owner must equal the SHA-512 hash proxies send");
        assertEquals(STEVE, response.uuid(), "uuid must still be returned");
        assertEquals("Steve", response.name());
        assertEquals("otis", response.source());
    }

    @Test
    void unknownNameStaysAnErrorWithoutOwner() {
        PlayerLookupService service = serviceResolving("Steve", new ResolvedPlayer(STEVE, "Steve", "otis"));

        assertInstanceOf(PlayerResolveDTO.Error.class, service.resolve("Alex"),
                "an unresolvable name must yield the Error variant");
    }
}
