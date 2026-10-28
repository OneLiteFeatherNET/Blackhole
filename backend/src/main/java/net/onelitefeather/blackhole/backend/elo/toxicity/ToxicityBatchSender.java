package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Sends one batch of message texts to the scorer and completes with one overall score per text, in order. */
@FunctionalInterface
public interface ToxicityBatchSender {

    CompletableFuture<List<Double>> send(List<String> texts);
}
