package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.guard.dto.GuardDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.util.Locale;
import java.util.Optional;

/** Reads the guard state for the panel and sets the manual level. The controller maps each result to a response. */
@Singleton
public class GuardService {

    private final GuardStateStore states;
    private final GuardTransitionService transitions;

    public GuardService(GuardStateStore states, GuardTransitionService transitions) {
        this.states = states;
        this.transitions = transitions;
    }

    public GuardDTO state() {
        Optional<GuardState> state = this.states.current();
        return state.<GuardDTO>map(GuardService::response)
                .orElseGet(() -> new GuardDTO.Error("the join guard state is missing", ErrorKind.NOT_FOUND));
    }

    /**
     * Sets the manual level. Lowering a level is not restricted, because it only opens the network.
     *
     * @param actorOid the token's oid, recorded in the level history
     */
    public GuardDTO setLevel(GuardDTO.CreateRequest request, String actorOid) {
        GuardLevel level;
        try {
            level = GuardLevel.valueOf(request.level().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return new GuardDTO.Error("level must be OFF, STRICT or LOCKDOWN", ErrorKind.VALIDATION);
        }
        return this.transitions.setManual(level, actorOid)
                .<GuardDTO>map(GuardService::response)
                .orElseGet(() -> new GuardDTO.Error("the join guard level could not be set, try again", ErrorKind.CONFLICT));
    }

    static GuardDTO.Response response(GuardState state) {
        return new GuardDTO.Response(state.manual().name(), state.automatic().name(), state.effective().name(),
                state.updatedAt());
    }
}
