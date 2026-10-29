package net.onelitefeather.blackhole.backend.rollback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An in-memory {@link RollbackJobStore} for unit tests. It follows the SQL rules: a job moves from {@code PREVIEW}
 * to {@code RUNNING} once, a lease is free when it has expired on the clock passed in, and only {@code PENDING}
 * items are handed out.
 */
final class InMemoryRollbackJobStore implements RollbackJobStore {

    private final Map<UUID, Job> jobs = new HashMap<>();
    private final Map<UUID, Map<String, Row>> items = new HashMap<>();
    private final Map<UUID, Long> startedAt = new HashMap<>();
    private final Map<UUID, String> lockedBy = new HashMap<>();
    private final Map<UUID, Long> lockedUntil = new HashMap<>();

    /** One frozen item and its state; mutated as the runner works through the job. */
    private static final class Row {
        final Item item;
        String state;
        String reasonCode;

        Row(Item item) {
            this.item = item;
            this.state = item.state();
        }
    }

    @Override
    public void insertPreview(PreviewJob job, List<FrozenItem> frozen) {
        this.jobs.put(job.id(), new Job(job.id(), "PREVIEW", job.requestedByOid(), selectorOid(job.selector()), null, null,
                job.previewExpiresAt()));
        Map<String, Row> rows = new LinkedHashMap<>();
        for (FrozenItem item : frozen) {
            rows.put(item.identifier(), new Row(new Item(item.identifier(), item.owner(), item.sectionKey(), "PENDING")));
        }
        this.items.put(job.id(), rows);
    }

    @Override
    public Optional<Job> find(UUID id) {
        return Optional.ofNullable(this.jobs.get(id));
    }

    @Override
    public boolean markRunning(UUID id, String reason, String idempotencyKey, long now) {
        Job job = this.jobs.get(id);
        if (job == null || !"PREVIEW".equals(job.state()) || now >= job.previewExpiresAt()) {
            return false;
        }
        this.jobs.put(id, new Job(id, "RUNNING", job.requestedByOid(), job.selectorOid(), reason, idempotencyKey, job.previewExpiresAt()));
        this.startedAt.put(id, now);
        return true;
    }

    @Override
    public Optional<UUID> claimRunningJob(String holder, long now, long leaseUntil) {
        Optional<UUID> claimed = this.startedAt.entrySet().stream()
                .filter(entry -> this.jobs.get(entry.getKey()).state().equals("RUNNING"))
                .filter(entry -> this.lockedUntil.get(entry.getKey()) == null || this.lockedUntil.get(entry.getKey()) < now
                        || holder.equals(this.lockedBy.get(entry.getKey())))
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .findFirst();
        claimed.ifPresent(id -> {
            this.lockedBy.put(id, holder);
            this.lockedUntil.put(id, leaseUntil);
        });
        return claimed;
    }

    @Override
    public void releaseLease(UUID id, String holder) {
        if (holder.equals(this.lockedBy.get(id))) {
            this.lockedBy.remove(id);
            this.lockedUntil.remove(id);
        }
    }

    @Override
    public List<Item> nextItems(UUID id, int limit) {
        return this.items.getOrDefault(id, Map.of()).values().stream()
                .filter(row -> "PENDING".equals(row.state))
                .sorted((a, b) -> a.item.identifier().compareTo(b.item.identifier()))
                .limit(limit)
                .map(row -> new Item(row.item.identifier(), row.item.owner(), row.item.sectionKey(), row.state))
                .collect(Collectors.toList());
    }

    @Override
    public void markItem(UUID id, String identifier, String state, String reasonCode, long now) {
        Row row = this.items.get(id).get(identifier);
        row.state = state;
        row.reasonCode = reasonCode;
    }

    @Override
    public long countOpen(UUID id) {
        return this.items.getOrDefault(id, Map.of()).values().stream().filter(row -> "PENDING".equals(row.state)).count();
    }

    @Override
    public Map<String, Long> countByState(UUID id) {
        return this.items.getOrDefault(id, Map.of()).values().stream()
                .collect(Collectors.groupingBy(row -> row.state, Collectors.counting()));
    }

    @Override
    public boolean markCompleted(UUID id, long now) {
        Job job = this.jobs.get(id);
        if (job == null || !"RUNNING".equals(job.state())) {
            return false;
        }
        this.jobs.put(id, new Job(id, "COMPLETED", job.requestedByOid(), job.selectorOid(), job.reason(), job.idempotencyKey(),
                job.previewExpiresAt()));
        return true;
    }

    private static UUID selectorOid(RollbackSelector selector) {
        return selector instanceof RollbackSelector.Staff staff ? staff.oid() : null;
    }

    @Override
    public boolean cancel(UUID id, long now) {
        Job job = this.jobs.get(id);
        if (job == null || !(job.state().equals("PREVIEW") || job.state().equals("RUNNING"))) {
            return false;
        }
        this.jobs.put(id, new Job(id, "CANCELLED", job.requestedByOid(), job.selectorOid(), job.reason(), job.idempotencyKey(),
                job.previewExpiresAt()));
        return true;
    }

    /** The state of one item, for assertions. */
    String stateOf(UUID id, String identifier) {
        return Objects.requireNonNull(this.items.get(id).get(identifier), "no such item").state;
    }

    /** The reason code of one item, for assertions; null when it has none. */
    String reasonOf(UUID id, String identifier) {
        return this.items.get(id).get(identifier).reasonCode;
    }

    /** Puts a job into a state directly, for tests that need a job that is not reachable by the normal path. */
    void expire(UUID id) {
        Job job = this.jobs.get(id);
        this.jobs.put(id, new Job(id, job.state(), job.requestedByOid(), job.selectorOid(), job.reason(), job.idempotencyKey(), 0L));
    }

    List<String> identifiers(UUID id) {
        return new ArrayList<>(this.items.get(id).keySet());
    }
}
