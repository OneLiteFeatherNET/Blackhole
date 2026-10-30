package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.dto.GuardSettingsDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Reads and replaces the unattended settings of the join guard. The controller maps each result to a response. A change
 * is in force at the next evaluation tick, because the tick reads the stored row.
 */
@Singleton
public class GuardSettingsService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final GuardSettingsStore store;
    private final DomainEventPublisher events;
    private final StaffAuditRecorder audit;
    private final Clock clock;

    public GuardSettingsService(GuardSettingsStore store, DomainEventPublisher events, StaffAuditRecorder audit, Clock clock) {
        this.store = store;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public GuardSettingsDTO read() {
        long now = this.clock.millis();
        return response(this.store.current(), now);
    }

    /**
     * Replaces the whole settings object. A refused request writes nothing. Every accepted save is audited, and the event is
     * published only when a value changed, so a save with the stored values does not announce a change.
     *
     * @param actor the verified administrator who saves the settings
     */
    public GuardSettingsDTO replace(GuardSettingsDTO.UpdateRequest request, Actor actor) {
        GuardSettingsRules.Verdict verdict = GuardSettingsRules.check(request);
        if (verdict instanceof GuardSettingsRules.Verdict.Refused refused) {
            return new GuardSettingsDTO.Error(refused.message(), ErrorKind.VALIDATION);
        }
        UnattendedSettings candidate = ((GuardSettingsRules.Verdict.Valid) verdict).settings();
        UnattendedSettings previous = this.store.current();
        long now = this.clock.millis();
        UnattendedSettings saved = candidate.savedAt(now, actor.oid().toString());
        this.store.save(saved);
        this.audit.record(actor.attribution(), actor, AuditAction.GUARD_SETTINGS_SET, AuditTarget.none());
        if (!previous.sameValuesAs(saved)) {
            this.events.publish(GuardSettingsEvents.CHANGED, GuardSettingsEvents.changed(saved));
        }
        return response(saved, now);
    }

    static GuardSettingsDTO.Response response(UnattendedSettings settings, long now) {
        Optional<NightWindowSchedule.Span> next = settings.nightWindowEnabled()
                ? NightWindowSchedule.next(settings.nightWindow(), settings.zone(), now)
                : Optional.empty();
        return new GuardSettingsDTO.Response(settings.unattendedLevel().name(), (int) settings.absenceThreshold().toMinutes(),
                settings.nightWindowEnabled(), format(settings.nightWindow().start()), format(settings.nightWindow().end()),
                settings.zone().getId(), settings.updatedAt(), settings.updatedByOid(),
                next.map(NightWindowSchedule.Span::startMillis).orElse(null),
                next.map(NightWindowSchedule.Span::endMillis).orElse(null));
    }

    private static String format(LocalTime time) {
        return time.format(HH_MM);
    }
}
