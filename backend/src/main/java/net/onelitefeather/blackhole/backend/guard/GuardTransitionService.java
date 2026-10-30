package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Applies a change of the manual or the unattended level. Each change is one conditional update of the guard row, so
 * when several replicas evaluate the same transition only one of them writes it: the replica whose update succeeded
 * writes the history row and publishes the event. No transaction is used.
 */
@Singleton
public class GuardTransitionService {

    static final String LEVEL_CHANGED_EVENT = "guard.level_changed";
    private static final int MANUAL_ATTEMPTS = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(GuardTransitionService.class);

    private final GuardStateStore states;
    private final GuardChangeStore changes;
    private final GuardPresenceService presence;
    private final UnattendedEvaluator evaluator;
    private final GuardSettingsStore settings;
    private final DomainEventPublisher events;
    private final GuardStateCache cache;
    private final Clock clock;

    public GuardTransitionService(
            GuardStateStore states,
            GuardChangeStore changes,
            GuardPresenceService presence,
            UnattendedEvaluator evaluator,
            GuardSettingsStore settings,
            DomainEventPublisher events,
            GuardStateCache cache,
            Clock clock
    ) {
        this.states = states;
        this.changes = changes;
        this.presence = presence;
        this.evaluator = evaluator;
        this.settings = settings;
        this.events = events;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Sets the manual level. A concurrent change is retried against the fresh row a few times.
     *
     * @param source MANUAL for the panel and the API, IN_GAME for the proxy, DISCORD for the bot
     * @return the state that is in force afterwards, or empty when the guard row is missing or kept losing the race
     */
    public Optional<GuardState> setManual(GuardLevel level, String actorOid, GuardChange.Source source) {
        for (int attempt = 0; attempt < MANUAL_ATTEMPTS; attempt++) {
            Optional<GuardState> current = this.states.current();
            if (current.isEmpty()) {
                return Optional.empty();
            }
            GuardState before = current.get();
            long now = this.clock.millis();
            GuardState next = GuardState.of(level, before.automatic(), now);
            if (this.states.compareAndSet(before, next)) {
                this.cache.reload();
                recordIfEffectiveChanged(before, next, source, actorOid);
                return Optional.of(next);
            }
        }
        return Optional.empty();
    }

    /**
     * Recomputes the unattended level from the staff presence and writes it when it differs. Called by the scheduled
     * tick; a lost race is left for the next tick.
     *
     * @return true when this call wrote a new automatic level
     */
    public boolean reconcileAutomatic() {
        Optional<GuardState> current = this.states.current();
        if (current.isEmpty()) {
            return false;
        }
        GuardState before = current.get();
        long now = this.clock.millis();
        UnattendedSettings stored = this.settings.current();
        GuardLevel automatic = this.evaluator.automaticLevel(stored, this.presence.staffPresent(), this.presence.lastStaffSeen(), now);
        if (automatic == before.automatic()) {
            return false;
        }
        GuardState next = GuardState.of(before.manual(), automatic, now);
        if (!this.states.compareAndSet(before, next)) {
            LOGGER.info("The join guard changed concurrently, the unattended level is re-evaluated at the next tick");
            return false;
        }
        this.cache.reload();
        GuardChange.Source source = automatic == GuardLevel.OFF ? GuardChange.Source.CLEARED : GuardChange.Source.UNATTENDED;
        recordIfEffectiveChanged(before, next, source, null);
        return true;
    }

    private void recordIfEffectiveChanged(GuardState before, GuardState after, GuardChange.Source source, String actorOid) {
        if (before.effective() == after.effective()) {
            return;
        }
        this.changes.append(new GuardChange(after.updatedAt(), before.effective(), after.effective(), source, actorOid));
        this.events.publish(LEVEL_CHANGED_EVENT, Map.of(
                "level", after.effective().name(),
                "previous", before.effective().name(),
                "manual", after.manual().name(),
                "unattended", after.automatic().name(),
                "changedAt", after.updatedAt(),
                "source", source.name()));
    }
}
