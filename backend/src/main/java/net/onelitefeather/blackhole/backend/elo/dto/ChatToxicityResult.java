package net.onelitefeather.blackhole.backend.elo.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ChatToxicityResult(boolean flagged, double score) {
}
