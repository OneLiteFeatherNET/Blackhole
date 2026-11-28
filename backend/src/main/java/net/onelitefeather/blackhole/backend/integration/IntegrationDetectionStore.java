package net.onelitefeather.blackhole.backend.integration;

import java.util.UUID;

/** Persistence of stored detections. The service decides what is stored; the store only writes and deletes rows. */
public interface IntegrationDetectionStore {

    /**
     * One detection to store. {@code ownerHash} is the SHA-512 hex of the Minecraft UUID; the raw UUID is never passed here.
     *
     * @param payloadJson the validated payload, serialised as JSON
     */
    record NewDetection(long integrationId, UUID detectionId, String kind, int schemaVersion, String ownerHash, String server,
                        long occurredAt, long receivedAt, String severity, String summary, String payloadJson) {
    }

    /**
     * Stores a detection unless one with the same (integration, detection id) is stored already. The stored row is never
     * changed by a second insert.
     *
     * @return {@code true} when a row was inserted, {@code false} for a duplicate
     */
    boolean insertIfAbsent(NewDetection detection);

    /**
     * Deletes the unattached detections whose occurrence time is before {@code cutoff} (epoch millis). Attached ones are kept.
     *
     * @return the number of rows deleted
     */
    int deleteUnattachedOccurredBefore(long cutoff);
}
