package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row-level slot writes against the real database, including the races they exist for.
 */
@Tag("integration")
class ProfileSlotsIntegrationTest extends PostgresIntegrationTest {

    private ApplicationContext context;
    private ProfileSlots slots;
    private PunishmentRepository punishments;
    private PunishmentProfileRepository profiles;

    @BeforeEach
    void start() {
        this.context = ApplicationContext.run(contextProperties());
        this.slots = this.context.getBean(ProfileSlots.class);
        this.punishments = this.context.getBean(PunishmentRepository.class);
        this.profiles = this.context.getBean(PunishmentProfileRepository.class);
    }

    @AfterEach
    void stop() {
        this.context.close();
    }

    private String punishment(String owner, String section, boolean permanent) {
        String identifier = UUID.randomUUID().toString();
        this.punishments.save(new PunishmentEntity(
                identifier, UUID.randomUUID(), owner, section, permanent, 1L, null, new HashMap<>()));
        return identifier;
    }

    private PunishmentProfileEntity profile(String owner) {
        return this.profiles.findById(owner).orElseThrow();
    }

    @Test
    void occupyingAnEmptySlotCreatesTheProfile() {
        String owner = "a1".repeat(64);

        ProfileSlots.Placement placement = this.slots.occupy(owner, "chat", punishment(owner, "chat", false));

        assertTrue(placement.occupied());
        assertTrue(placement.profileCreated(), "first punishment of the owner creates the profile");
        assertEquals(1, profile(owner).getActive().size());
    }

    @Test
    void occupyingATakenSlotMovesTheOldPunishmentToHistory() {
        String owner = "a2".repeat(64);
        String first = punishment(owner, "chat", false);
        String second = punishment(owner, "chat", false);
        this.slots.occupy(owner, "chat", first);

        ProfileSlots.Placement placement = this.slots.occupy(owner, "chat", second);

        assertEquals(first, placement.replaced().orElseThrow());
        assertEquals(second, profile(owner).getActive().get("chat").getIdentifier());
        assertEquals(List.of(first), profile(owner).getHistory().stream().map(PunishmentEntity::getIdentifier).toList());
    }

    @Test
    void aPermanentPunishmentIsNeverReplaced() {
        String owner = "a3".repeat(64);
        String permanent = punishment(owner, "network", true);
        this.slots.occupy(owner, "network", permanent);

        ProfileSlots.Placement placement = this.slots.occupy(owner, "network", punishment(owner, "network", false));

        assertFalse(placement.occupied(), "a temporary punishment must not push out a permanent one");
        assertEquals(permanent, profile(owner).getActive().get("network").getIdentifier());
    }

    @Test
    void concurrentOccupationsOfDifferentSectionsAllStayActive() throws Exception {
        String owner = "a4".repeat(64);
        String chat = punishment(owner, "chat", false);
        String network = punishment(owner, "network", false);

        runTogether(() -> this.slots.occupy(owner, "chat", chat), () -> this.slots.occupy(owner, "network", network));

        assertEquals(2, profile(owner).getActive().size(), "neither section may overwrite the other");
    }

    @Test
    void concurrentOccupationsOfTheSameSectionLeaveOneActiveAndTheOtherInHistory() throws Exception {
        String owner = "a5".repeat(64);
        String one = punishment(owner, "chat", false);
        String two = punishment(owner, "chat", false);

        runTogether(() -> this.slots.occupy(owner, "chat", one), () -> this.slots.occupy(owner, "chat", two));

        PunishmentProfileEntity stored = profile(owner);
        assertEquals(1, stored.getActive().size());
        assertEquals(1, stored.getHistory().size(), "the loser of the race is kept in history, not lost");
        assertEquals(2, List.of(stored.getActive().get("chat").getIdentifier(), stored.getHistory().getFirst().getIdentifier())
                .stream().distinct().count());
    }

    @Test
    void clearingOnlyWorksWhileTheSlotStillHoldsTheExpectedPunishment() {
        String owner = "a6".repeat(64);
        String old = punishment(owner, "chat", false);
        String current = punishment(owner, "chat", false);
        this.slots.occupy(owner, "chat", old);
        this.slots.occupy(owner, "chat", current);

        assertFalse(this.slots.clearIf(owner, "chat", old), "the slot no longer holds the old punishment");
        assertEquals(current, profile(owner).getActive().get("chat").getIdentifier());
        assertTrue(this.slots.clearIf(owner, "chat", current));
        assertTrue(profile(owner).getActive().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergingSessionInfoKeepsPunishmentsAndEarlierFields() {
        String owner = "a7".repeat(64);
        this.slots.occupy(owner, "chat", punishment(owner, "chat", false));
        this.slots.mergeSessionInfo(owner, Map.of("protocolVersion", 770, "lastSeenAt", 1L));

        this.slots.mergeSessionInfo(owner, Map.of("clientBrand", "vanilla", "lastSeenAt", 2L));

        PunishmentProfileEntity stored = profile(owner);
        Map<String, Object> info = (Map<String, Object>) stored.getMetaData().get("sessionInfo");
        assertEquals("vanilla", info.get("clientBrand"));
        assertEquals(770, ((Number) info.get("protocolVersion")).intValue(), "an earlier field survives the merge");
        assertEquals(2L, ((Number) info.get("lastSeenAt")).longValue());
        assertEquals(1, stored.getActive().size(), "the punishment is untouched by the telemetry write");
    }

    @Test
    void mergingSessionInfoForAnUnknownOwnerCreatesTheProfile() {
        String owner = "a8".repeat(64);

        this.slots.mergeSessionInfo(owner, Map.of("clientBrand", "fabric"));

        assertTrue(this.profiles.findById(owner).isPresent());
    }

    @SafeVarargs
    private static void runTogether(Callable<?>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
