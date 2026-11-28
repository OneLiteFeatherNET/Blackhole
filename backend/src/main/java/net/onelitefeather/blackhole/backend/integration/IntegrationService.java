package net.onelitefeather.blackhole.backend.integration;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/**
 * Owns the integration registry: validated, audited writes of who may report detections. Expected failures are
 * returned as {@link IntegrationDTO.Error}, never thrown.
 */
@Singleton
public class IntegrationService {

    private final IntegrationStore store;
    private final Clock clock;
    private final StaffAuditRecorder audit;

    public IntegrationService(IntegrationStore store, Clock clock, StaffAuditRecorder audit) {
        this.store = store;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Registers a source. Validation (400) runs first; a taken source system or client id is a conflict (409).
     */
    public IntegrationDTO create(IntegrationDTO.CreateRequest request, Actor author) {
        IntegrationSpec spec = request.toSpec();
        IntegrationDTO.Error problem = problemWith(spec);
        if (problem != null) {
            return problem;
        }
        OptionalLong id = this.store.insert(spec, author.oid(), this.clock.millis());
        if (id.isEmpty()) {
            return new IntegrationDTO.Error("an integration for source system '" + spec.sourceSystem()
                    + "' or this client id is already registered", ErrorKind.CONFLICT);
        }
        this.audit.record(author.attribution(), author, AuditAction.INTEGRATION_REGISTER, AuditTarget.ofIntegration(spec.sourceSystem()));
        return get(id.getAsLong());
    }

    /**
     * Replaces the editable fields. Turning a registration off is audited as a disable, every other change as an update.
     */
    public IntegrationDTO update(long id, IntegrationDTO.UpdateRequest request, Actor author) {
        IntegrationStore.Stored stored = this.store.find(id).orElse(null);
        if (stored == null) {
            return notFound(id);
        }
        IntegrationSpec current = stored.spec();
        IntegrationSpec spec = new IntegrationSpec(current.sourceSystem(), request.displayName(), current.entraClientId(),
                request.enabled(), orEmpty(request.allowedKinds()), orEmpty(request.allowedTasks()),
                orEmpty(request.allowedStaticServers()));
        IntegrationDTO.Error problem = problemWith(spec);
        if (problem != null) {
            return problem;
        }
        if (!this.store.update(id, spec, author.oid(), this.clock.millis())) {
            return notFound(id);
        }
        AuditAction action = current.enabled() && !spec.enabled() ? AuditAction.INTEGRATION_DISABLE : AuditAction.INTEGRATION_UPDATE;
        this.audit.record(author.attribution(), author, action, AuditTarget.ofIntegration(current.sourceSystem()));
        return get(id);
    }

    /** One registration by id, or a not found error. */
    public IntegrationDTO get(long id) {
        return this.store.find(id).<IntegrationDTO>map(IntegrationService::toResponse).orElseGet(() -> notFound(id));
    }

    /** Every registration, enabled or not. */
    public List<IntegrationDTO.Response> list() {
        return this.store.list().stream().map(IntegrationService::toResponse).toList();
    }

    private static IntegrationDTO.Error problemWith(IntegrationSpec spec) {
        List<String> errors = IntegrationValidator.validate(spec);
        return errors.isEmpty() ? null : new IntegrationDTO.Error(String.join("; ", errors), ErrorKind.VALIDATION);
    }

    private static IntegrationDTO.Error notFound(long id) {
        return new IntegrationDTO.Error("there is no integration registration " + id, ErrorKind.NOT_FOUND);
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    static IntegrationDTO.Response toResponse(IntegrationStore.Stored stored) {
        IntegrationSpec spec = stored.spec();
        return new IntegrationDTO.Response(stored.id(), spec.sourceSystem(), spec.displayName(), spec.entraClientId(),
                spec.enabled(), spec.allowedKinds(), spec.allowedTasks(), spec.allowedStaticServers(),
                Instant.ofEpochMilli(stored.createdAt()), Instant.ofEpochMilli(stored.updatedAt()));
    }
}
