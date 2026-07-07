package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ChatToxicityResult(boolean flagged, double score) {
}
