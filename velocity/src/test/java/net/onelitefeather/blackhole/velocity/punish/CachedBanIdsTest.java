package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.enforcement.CachedBanIds;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedBanIdsTest {

    private static final String PUNISHMENT = "p-1";
    private static final String BAN_ID = "7K3M9QXA";

    private final StubGateway backend = new StubGateway();
    private final MutableClock clock = new MutableClock();
    /** Every message and argument the code under test hands to its logger, whatever the level. */
    private final List<String> logged = new ArrayList<>();
    private final CachedBanIds banIds = new CachedBanIds(backend, clock, Duration.ofSeconds(2), recordingLogger(logged));

    private static Logger recordingLogger(List<String> sink) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class}, (proxy, method, args) -> {
            if (method.getName().startsWith("is") && method.getReturnType() == boolean.class) {
                return true;
            }
            if (args != null) {
                sink.add(Arrays.deepToString(args));
            }
            return null;
        });
    }

    @Test
    void theBanIdOfAPunishmentIsReturned() {
        assertEquals(Optional.of(BAN_ID), banIds.of(PUNISHMENT));
    }

    @Test
    void aMissingPunishmentIdentifierIsNotLookedUp() {
        assertTrue(banIds.of(null).isEmpty(), "without an identifier there is no Ban ID");
        assertTrue(banIds.of(" ").isEmpty(), "a blank identifier has no Ban ID");
        assertTrue(backend.banIdPunishments.isEmpty(), "nothing may be asked of the backend");
    }

    @Test
    void aFailedLookupYieldsNoBanId() {
        backend.banIdResult = CompletableFuture.failedFuture(new ApiException(503, "down"));

        assertTrue(banIds.of(PUNISHMENT).isEmpty(), "a failing backend must not break the screen");
    }

    @Test
    void aLookupThatTimesOutYieldsNoBanId() {
        backend.banIdResult = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException();
            }
        };

        assertTrue(banIds.of(PUNISHMENT).isEmpty(), "a slow backend must not hold the screen back");
    }

    @Test
    void aLegacyHexShortIdIsNeverAccepted() {
        backend.banIdResult = CompletableFuture.completedFuture("#A1B2C3D4");

        assertTrue(banIds.of(PUNISHMENT).isEmpty(), "only an eight character Crockford base32 value may reach a screen");
    }

    @Test
    void anAnswerOfTheWrongShapeIsNeverAccepted() {
        backend.banIdResult = CompletableFuture.completedFuture("7K3M9QXAZZ");
        assertTrue(banIds.of("p-too-long").isEmpty(), "too long");

        backend.banIdResult = CompletableFuture.completedFuture("7k3m9qxa");
        assertTrue(banIds.of("p-lower").isEmpty(), "lower case is not what the backend issues");

        backend.banIdResult = CompletableFuture.completedFuture(null);
        assertTrue(banIds.of("p-null").isEmpty(), "no value");
    }

    @Test
    void aSecondLookupWithinTheCacheTimeUsesTheCache() {
        banIds.of(PUNISHMENT);
        clock.advance(Duration.ofMinutes(4));

        assertEquals(Optional.of(BAN_ID), banIds.of(PUNISHMENT));
        assertEquals(List.of(PUNISHMENT), backend.banIdPunishments, "a retrying player must not hit the backend each time");
    }

    @Test
    void theCacheExpires() {
        banIds.of(PUNISHMENT);
        clock.advance(Duration.ofMinutes(6));
        backend.banIdResult = CompletableFuture.completedFuture("ZZZZ0000");

        assertEquals(Optional.of("ZZZZ0000"), banIds.of(PUNISHMENT), "a reissued Ban ID must show up after the cache expired");
    }

    @Test
    void aFailureIsRetriedSoonerThanASuccessExpires() {
        backend.banIdResult = CompletableFuture.failedFuture(new ApiException(503, "down"));
        banIds.of(PUNISHMENT);
        banIds.of(PUNISHMENT);
        assertEquals(1, backend.banIdPunishments.size(), "a failing backend is not asked again at once");

        clock.advance(Duration.ofSeconds(11));
        backend.banIdResult = CompletableFuture.completedFuture(BAN_ID);

        assertEquals(Optional.of(BAN_ID), banIds.of(PUNISHMENT), "the player gets the Ban ID as soon as the backend is back");
    }

    @Test
    void theBanIdIsNeverLogged() {
        banIds.of(PUNISHMENT);
        backend.banIdResult = CompletableFuture.failedFuture(new ApiException(500, "boom " + BAN_ID));
        banIds.of("p-2");
        backend.banIdResult = CompletableFuture.completedFuture("#A1B2C3D4");
        banIds.of("p-3");

        assertFalse(logged.stream().anyMatch(line -> line.contains(BAN_ID)), "the Ban ID must not reach a log, got: " + logged);
    }

    @Test
    void theOnlyStateIsAnInMemoryMap() {
        List<String> stateful = Arrays.stream(CachedBanIds.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(f -> f.getType().getSimpleName())
                .toList();

        assertEquals(List.of("BackendGateway", "Clock", "Duration", "Logger", "Map"), stateful.stream().sorted().toList(),
                "no file, store or shared cache may hold a Ban ID");
    }
}
