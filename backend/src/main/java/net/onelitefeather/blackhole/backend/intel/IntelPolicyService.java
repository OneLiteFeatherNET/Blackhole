package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.intel.dto.PolicyDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and sets the login policy per class. Only an admin may set DENY; every change is audited with the previous
 * value, so the audit entry shows what the policy was and what it became.
 */
@Singleton
public class IntelPolicyService {

    private final IntelRecordStore store;
    private final IntelPolicyCache cache;
    private final StaffAuditRecorder audit;
    private final Clock clock;

    public IntelPolicyService(IntelRecordStore store, IntelPolicyCache cache, StaffAuditRecorder audit, Clock clock) {
        this.store = store;
        this.cache = cache;
        this.audit = audit;
        this.clock = clock;
    }

    /** @return the policy of every class; a class without a stored row is FLAG */
    public PolicyDTO list() {
        Map<IntelClass, IntelPolicy> stored = this.store.loadPolicies();
        List<PolicyDTO.Response> policies = new ArrayList<>();
        for (IntelClass classification : IntelClass.values()) {
            policies.add(new PolicyDTO.Response(classification, stored.getOrDefault(classification, IntelPolicy.FLAG), null));
        }
        return new PolicyDTO.Listing(policies);
    }

    /**
     * Sets the policy of one class.
     *
     * @return the stored policy with the value it replaced, or the error when a moderator asks for DENY
     */
    public PolicyDTO update(PolicyDTO.UpdateRequest request, Actor actor) {
        if (request.policy() == IntelPolicy.DENY && !actor.admin()) {
            return new PolicyDTO.Error("only an admin may set a login policy to DENY", ErrorKind.FORBIDDEN_POLICY);
        }
        IntelClass classification = request.classification();
        IntelPolicy previous = this.store.loadPolicies().getOrDefault(classification, IntelPolicy.FLAG);
        this.store.savePolicy(classification, request.policy(), this.clock.millis());
        this.cache.reload();
        this.audit.record(actor.attribution(), actor, AuditAction.INTEL_POLICY_UPDATE,
                new AuditTarget("POLICY", classification.name() + ":" + previous.name() + ">" + request.policy().name()));
        return new PolicyDTO.Response(classification, request.policy(), previous);
    }
}
