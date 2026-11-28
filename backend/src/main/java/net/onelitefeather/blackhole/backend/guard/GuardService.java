package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.audit.Surface;
import net.onelitefeather.blackhole.backend.audit.SurfaceResolver;
import net.onelitefeather.blackhole.backend.guard.dto.GuardDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffDelegation;

import java.util.Locale;
import java.util.Optional;

/** Reads the guard state for the panel and sets the manual level. The controller maps each result to a response. */
@Singleton
public class GuardService {

    private final GuardStateStore states;
    private final GuardTransitionService transitions;
    private final StaffDelegation delegation;
    private final StaffAuditRecorder audit;

    public GuardService(GuardStateStore states, GuardTransitionService transitions, StaffDelegation delegation,
                        StaffAuditRecorder audit) {
        this.states = states;
        this.transitions = transitions;
        this.delegation = delegation;
        this.audit = audit;
    }

    public GuardDTO state() {
        Optional<GuardState> state = this.states.current();
        return state.<GuardDTO>map(GuardService::response)
                .orElseGet(() -> new GuardDTO.Error("the join guard state is missing", ErrorKind.NOT_FOUND));
    }

    /**
     * Sets the manual level. Lowering a level is not restricted, because it only opens the network. The change is recorded
     * with the staff member it is for (the delegated one for the proxy and the bot), and with the surface it came from.
     *
     * @param actor the verified caller; its {@code onBehalfOf} names the staff member for a machine caller
     */
    public GuardDTO setLevel(GuardDTO.CreateRequest request, Actor actor) {
        StaffDelegation.Outcome delegated = this.delegation.attributeGuard(actor, request.onBehalfOf());
        if (delegated instanceof StaffDelegation.Outcome.Refused refused) {
            return new GuardDTO.Error(refused.message(), refused.errorKind());
        }
        Attribution by = ((StaffDelegation.Outcome.Allowed) delegated).attribution();
        GuardLevel level;
        try {
            level = GuardLevel.valueOf(request.level().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return new GuardDTO.Error("level must be OFF, STRICT or LOCKDOWN", ErrorKind.VALIDATION);
        }
        Surface surface = SurfaceResolver.resolve(actor, !by.source().equals(by.recordedByOid()));
        return this.transitions.setManual(level, by.source().toString(), sourceOf(surface))
                .<GuardDTO>map(state -> {
                    this.audit.record(by, actor, AuditAction.GUARD_LEVEL_SET, AuditTarget.none());
                    return response(state);
                })
                .orElseGet(() -> new GuardDTO.Error("the join guard level could not be set, try again", ErrorKind.CONFLICT));
    }

    /** The change source of a manual level set: the surface it came from, MANUAL for the panel and the API. */
    static GuardChange.Source sourceOf(Surface surface) {
        return switch (surface) {
            case IN_GAME -> GuardChange.Source.IN_GAME;
            case DISCORD -> GuardChange.Source.DISCORD;
            case PANEL, API -> GuardChange.Source.MANUAL;
        };
    }

    static GuardDTO.Response response(GuardState state) {
        return new GuardDTO.Response(state.manual().name(), state.automatic().name(), state.effective().name(),
                state.updatedAt());
    }
}
