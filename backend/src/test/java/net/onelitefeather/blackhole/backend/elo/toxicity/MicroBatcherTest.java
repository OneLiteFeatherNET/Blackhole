package net.onelitefeather.blackhole.backend.elo.toxicity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicroBatcherTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<List<String>> sent = new ArrayList<>();

    private MicroBatcher batcher() {
        return new MicroBatcher(16, Duration.ofMillis(5), scheduler, texts -> {
            sent.add(List.copyOf(texts));
            return CompletableFuture.completedFuture(texts.stream().map(t -> 0.5).toList());
        });
    }

    @Test
    void sixteenConcurrentRequestsAreSentAsOneBatch() {
        MicroBatcher batcher = batcher();

        for (int i = 0; i < 16; i++) {
            batcher.submit("message " + i);
        }

        assertEquals(1, sent.size(), "sixteen requests must go out as a single batch");
        assertEquals(16, sent.getFirst().size(), "the batch must carry all sixteen messages");
    }

    @Test
    void aLoneRequestIsNotSentBeforeTheLingerHasPassed() {
        batcher().submit("alone");

        assertTrue(sent.isEmpty(), "a lone message waits for the linger before it is sent");
    }

    @Test
    void aLoneRequestIsSentWhenTheLingerFires() {
        batcher().submit("alone");

        scheduler.runNext();

        assertEquals(List.of(List.of("alone")), sent, "after the linger the lone message goes out as a batch of one");
    }
}
