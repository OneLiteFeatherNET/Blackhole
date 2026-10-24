package net.onelitefeather.blackhole.backend.publicapi;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One open appeal per punishment under concurrent filing (the waiting period is zero in this application). */
@Tag("integration")
class PublicAppealEligibilityIntegrationTest extends PublicApiTestBase {

    @SuppressWarnings("unchecked")
    private long appealsOf(String punishment) {
        List<Map<String, Object>> all = (List<Map<String, Object>>) get("/appeal?size=100").json().get("content");
        return all.stream().filter(a -> punishment.equals(a.get("punishmentIdentifier"))).count();
    }

    @Test
    void exactlyOneOfManyConcurrentFilingsWinsAndTheRestAreRefused() throws Exception {
        String punishment = punish("e2".repeat(64), "pub-race");
        String token = sessionFor(punishment);
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String statement = "Please review my ban, attempt " + i;
                Callable<Integer> filing = () -> {
                    ready.countDown();
                    go.await();
                    return playerPost("/public/appeal", token, Map.of("conditionsVersion", 1, "statement", statement)).status();
                };
                results.add(pool.submit(filing));
            }
            ready.await();
            go.countDown();
            int ok = 0;
            int refused = 0;
            for (Future<Integer> result : results) {
                int status = result.get();
                if (status == 200) {
                    ok++;
                } else if (status == 409) {
                    refused++;
                }
            }

            assertEquals(1, ok, "one appeal is filed");
            assertEquals(callers - 1, refused, "every other is a 409, never a 500");
            assertEquals(1, appealsOf(punishment), "one appeal row exists");
        } finally {
            pool.shutdownNow();
        }
    }
}
