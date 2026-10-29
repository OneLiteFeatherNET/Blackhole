package net.onelitefeather.blackhole.backend.firewall;

import net.onelitefeather.blackhole.backend.ipban.IpBanRecord;
import net.onelitefeather.blackhole.backend.ipban.IpBanStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory ban rows shared by one or more feed builders, the way replicas share one table. It counts the reads of
 * {@link #active(long)} and {@link #marker()}; {@link #active(long)} can be made to fail. Each test builds its own.
 */
final class FeedTestStore implements IpBanStore {

    final List<IpBanRecord> rows = new ArrayList<>();
    final AtomicInteger activeReads = new AtomicInteger();
    final AtomicInteger markerReads = new AtomicInteger();
    volatile boolean failing;

    @Override
    public List<IpBanRecord> active(long now) {
        this.activeReads.incrementAndGet();
        if (this.failing) {
            throw new IllegalStateException("database is down");
        }
        return this.rows.stream().filter(row -> row.isActive(now)).toList();
    }

    @Override
    public Marker marker() {
        this.markerReads.incrementAndGet();
        long created = this.rows.stream().mapToLong(IpBanRecord::createdAt).max().orElse(0);
        long lifted = this.rows.stream().filter(row -> row.liftedAt() != null).mapToLong(IpBanRecord::liftedAt).max().orElse(0);
        return new Marker(this.rows.size(), created, lifted);
    }

    @Override
    public Optional<IpBanRecord> find(UUID id) {
        return this.rows.stream().filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public List<IpBanRecord> newest(int limit) {
        return List.of();
    }

    @Override
    public boolean insert(IpBanRecord record) {
        return this.rows.add(record);
    }

    @Override
    public boolean lift(UUID id, UUID liftedBy, String reason, long at) {
        for (int i = 0; i < this.rows.size(); i++) {
            IpBanRecord row = this.rows.get(i);
            if (row.id().equals(id) && row.liftedAt() == null) {
                this.rows.set(i, new IpBanRecord(row.id(), row.network(), row.reason(), row.templateKey(), row.source(),
                        row.punishmentId(), row.caseId(), row.actorOid(), row.createdAt(), row.expiresAt(), at, liftedBy, reason));
                return true;
            }
        }
        return false;
    }

    @Override
    public int liftExpired(String range, long now) {
        return 0;
    }

    @Override
    public int deleteClosedBefore(long cutoff) {
        return 0;
    }
}
