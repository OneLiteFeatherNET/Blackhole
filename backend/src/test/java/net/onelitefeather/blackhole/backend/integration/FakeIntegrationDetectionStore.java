package net.onelitefeather.blackhole.backend.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Keeps detections in memory with the uniqueness rule of the table; {@link #markAttached} stands in for the attach path. */
final class FakeIntegrationDetectionStore implements IntegrationDetectionStore {

    private final List<NewDetection> rows = new ArrayList<>();
    private final Set<UUID> attached = new HashSet<>();

    @Override
    public synchronized boolean insertIfAbsent(NewDetection detection) {
        boolean taken = this.rows.stream().anyMatch(row -> row.integrationId() == detection.integrationId()
                && row.detectionId().equals(detection.detectionId()));
        if (taken) {
            return false;
        }
        this.rows.add(detection);
        return true;
    }

    @Override
    public synchronized int deleteUnattachedOccurredBefore(long cutoff) {
        int before = this.rows.size();
        this.rows.removeIf(row -> row.occurredAt() < cutoff && !this.attached.contains(row.detectionId()));
        return before - this.rows.size();
    }

    synchronized List<NewDetection> stored() {
        return List.copyOf(this.rows);
    }

    synchronized void markAttached(UUID detectionId) {
        this.attached.add(detectionId);
    }
}
