package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.kick.KickStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The semantics of {@code JdbcKickStore} in memory, for unit tests. */
public final class InMemoryKickStore implements KickStore {

    private final Map<UUID, Action> actions = new HashMap<>();
    private final Map<UUID, Map<String, Delivery>> deliveries = new HashMap<>();

    @Override
    public boolean insertAction(Action action) {
        return this.actions.putIfAbsent(action.id(), action) == null;
    }

    @Override
    public Optional<Action> findAction(UUID id) {
        return Optional.ofNullable(this.actions.get(id));
    }

    @Override
    public void recordDelivery(UUID subjectId, Delivery delivery) {
        this.deliveries.computeIfAbsent(subjectId, id -> new HashMap<>()).merge(delivery.proxyId(), delivery,
                (old, next) -> new Delivery(old.proxyId(), old.found() || next.found(), old.moved() || next.moved(), old.ackedAt()));
    }

    @Override
    public List<Delivery> deliveries(UUID subjectId) {
        return new ArrayList<>(this.deliveries.getOrDefault(subjectId, Map.of()).values());
    }

    public int actionCount() {
        return this.actions.size();
    }
}
