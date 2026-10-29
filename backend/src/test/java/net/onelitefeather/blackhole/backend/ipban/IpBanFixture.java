package net.onelitefeather.blackhole.backend.ipban;

import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.events.EventTransport;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** A fresh in-memory IP ban setup for one test: store, clock, recorded events and the service. */
public final class IpBanFixture {

    public static final long NOW = 1_800_000_000_000L;
    public static final UUID MODERATOR_OID = UUID.fromString("0a0a0a0a-0000-4000-8000-000000000001");
    public static final UUID ADMIN_OID = UUID.fromString("0a0a0a0a-0000-4000-8000-000000000002");

    public final FakeStore store = new FakeStore();
    final List<DomainEvent> events = new ArrayList<>();
    final List<String> eventTypes = new ArrayList<>();
    public final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    final IpBanService service;

    public IpBanFixture() {
        EventTransport transport = (eventType, event) -> {
            this.eventTypes.add(eventType);
            this.events.add(event);
            return CompletableFuture.completedFuture(null);
        };
        DomainEventPublisher publisher = new DomainEventPublisher(transport, this.clock);
        this.service = new IpBanService(this.store, new IpBanProtection(""), new IpBanEvents(publisher),
                this.clock, Duration.ofDays(30));
    }

    public static Actor moderator() {
        return new Actor(MODERATOR_OID, "Moderator", ActorKind.HUMAN, false, false);
    }

    public static Actor admin() {
        return new Actor(ADMIN_OID, "Admin", ActorKind.HUMAN, false, true);
    }

    /** In-memory stand-in for the JDBC store, with the same partial-unique rule for active ranges. */
    public static final class FakeStore implements IpBanStore {

        public final Map<UUID, IpBanRecord> rows = new HashMap<>();

        @Override
        public Optional<IpBanRecord> find(UUID id) {
            return Optional.ofNullable(this.rows.get(id));
        }

        @Override
        public List<IpBanRecord> active(long now) {
            return this.rows.values().stream().filter(row -> row.isActive(now)).toList();
        }

        @Override
        public List<IpBanRecord> newest(int limit) {
            return this.rows.values().stream().sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt())).limit(limit).toList();
        }

        @Override
        public boolean insert(IpBanRecord record) {
            boolean clash = this.rows.values().stream()
                    .anyMatch(row -> row.liftedAt() == null && row.range().equals(record.range()));
            if (clash) {
                return false;
            }
            this.rows.put(record.id(), record);
            return true;
        }

        @Override
        public boolean lift(UUID id, UUID liftedBy, String reason, long at) {
            IpBanRecord row = this.rows.get(id);
            if (row == null || row.liftedAt() != null) {
                return false;
            }
            this.rows.put(id, new IpBanRecord(row.id(), row.network(), row.reason(), row.templateKey(), row.source(),
                    row.punishmentId(), row.caseId(), row.actorOid(), row.createdAt(), row.expiresAt(), at, liftedBy, reason));
            return true;
        }

        @Override
        public int liftExpired(String range, long now) {
            int count = 0;
            for (IpBanRecord row : List.copyOf(this.rows.values())) {
                if (row.liftedAt() == null && row.range().equals(range) && row.expiresAt() != null && row.expiresAt() <= now) {
                    this.rows.put(row.id(), new IpBanRecord(row.id(), row.network(), row.reason(), row.templateKey(), row.source(),
                            row.punishmentId(), row.caseId(), row.actorOid(), row.createdAt(), row.expiresAt(), now, null, "EXPIRED"));
                    count++;
                }
            }
            return count;
        }

        @Override
        public int deleteClosedBefore(long cutoff) {
            int before = this.rows.size();
            this.rows.values().removeIf(row -> (row.liftedAt() != null && row.liftedAt() < cutoff)
                    || (row.liftedAt() == null && row.expiresAt() != null && row.expiresAt() < cutoff));
            return before - this.rows.size();
        }
    }
}
