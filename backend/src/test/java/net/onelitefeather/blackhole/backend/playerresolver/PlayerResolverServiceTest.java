package net.onelitefeather.blackhole.backend.playerresolver;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerResolverServiceTest {

    private static PlayerResolverService serviceOf(List<PlayerResolver> resolvers) {
        return new PlayerResolverService(resolvers, new PlayerResolveCache(Duration.ofMinutes(5), 100));
    }

    private static PlayerResolver fixedResolver(String forName, ResolvedPlayer result) {
        return candidate -> candidate.equals(forName) ? Optional.of(result) : Optional.empty();
    }

    @Test
    void returnsTheFirstResolverInOrderThatHasAHit() {
        ResolvedPlayer fromOtis = new ResolvedPlayer(UUID.randomUUID(), "Steve", "otis");
        ResolvedPlayer fromMojang = new ResolvedPlayer(UUID.randomUUID(), "Steve", "mojang");
        PlayerResolverService service = serviceOf(List.of(
                fixedResolver("Steve", fromOtis),
                fixedResolver("Steve", fromMojang)
        ));

        assertEquals(Optional.of(fromOtis), service.resolve("Steve"));
    }

    @Test
    void fallsThroughToTheNextResolverWhenTheFirstMisses() {
        ResolvedPlayer fromMojang = new ResolvedPlayer(UUID.randomUUID(), "Alex", "mojang");
        PlayerResolverService service = serviceOf(List.of(
                name -> Optional.empty(),
                fixedResolver("Alex", fromMojang)
        ));

        assertEquals(Optional.of(fromMojang), service.resolve("Alex"));
    }

    @Test
    void aResolverThrowingDoesNotBreakTheChain() {
        ResolvedPlayer fallback = new ResolvedPlayer(UUID.randomUUID(), "Herobrine", "namemc");
        PlayerResolverService service = serviceOf(List.of(
                name -> {
                    throw new RuntimeException("boom");
                },
                fixedResolver("Herobrine", fallback)
        ));

        assertEquals(Optional.of(fallback), service.resolve("Herobrine"));
    }

    @Test
    void returnsEmptyWhenNoResolverHasAMatch() {
        PlayerResolverService service = serviceOf(List.of(name -> Optional.empty()));

        assertTrue(service.resolve("Nobody").isEmpty());
    }

    @Test
    void cachesAHitSoTheSameResolverIsNotCalledTwice() {
        ResolvedPlayer resolved = new ResolvedPlayer(UUID.randomUUID(), "Notch", "otis");
        int[] calls = {0};
        PlayerResolverService service = serviceOf(List.of(name -> {
            calls[0]++;
            return Optional.of(resolved);
        }));

        service.resolve("Notch");
        service.resolve("Notch");

        assertEquals(1, calls[0]);
    }
}
