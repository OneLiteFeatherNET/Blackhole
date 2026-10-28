package net.onelitefeather.blackhole.velocity;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.WarningResponse;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A backend for the warning flow: what it answers is set per test, what was asked is recorded. Only the
 * methods the warning code calls answer; any other call fails loudly.
 */
public final class FakeWarningBackend {

    /** The template key and the warningOnly flag of each apply call, as {@code key|flag}. */
    public final List<String> applied = new ArrayList<>();
    public final List<PunishTemplateResponse> templates = new ArrayList<>();
    public PunishmentApplyResponse applyResponse = new PunishmentApplyResponse();
    public ApiException applyFailure;
    /** The warnings by id; reading an unknown id answers 404. */
    public final Map<String, WarningResponse> warnings = new HashMap<>();
    /** Thrown by every warning read, when set. */
    public ApiException warningFailure;
    /** The ids that were read. */
    public final List<String> warningReads = new ArrayList<>();
    /** What the pending read answers, and the owners it was asked for. */
    public List<WarningResponse> pending = new ArrayList<>();
    public ApiException pendingFailure;
    public final List<String> pendingOwners = new ArrayList<>();
    /** Each acknowledgement that reached the backend, as {@code id|owner}. */
    public final List<String> acknowledged = new ArrayList<>();
    public ApiException acknowledgeFailure;

    public BackendGateway gateway() {
        return (BackendGateway) Proxy.newProxyInstance(
                FakeWarningBackend.class.getClassLoader(), new Class<?>[]{BackendGateway.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "templates" -> this.templates;
                    case "apply" -> {
                        this.applied.add(args[1] + "|" + args[4]);
                        if (this.applyFailure != null) {
                            throw this.applyFailure;
                        }
                        yield this.applyResponse;
                    }
                    case "warning" -> {
                        String id = (String) args[0];
                        this.warningReads.add(id);
                        if (this.warningFailure != null) {
                            throw this.warningFailure;
                        }
                        WarningResponse warning = this.warnings.get(id);
                        if (warning == null) {
                            throw new ApiException(404, "no such warning");
                        }
                        yield warning;
                    }
                    case "pendingWarnings" -> {
                        this.pendingOwners.add((String) args[0]);
                        if (this.pendingFailure != null) {
                            throw this.pendingFailure;
                        }
                        yield this.pending;
                    }
                    case "acknowledgeWarning" -> {
                        this.acknowledged.add(args[0] + "|" + args[1]);
                        if (this.acknowledgeFailure != null) {
                            throw this.acknowledgeFailure;
                        }
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeWarningBackend";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A warning of the owner, with the given reason and no acknowledgement yet. */
    public static WarningResponse warning(String id, String owner, String reason) {
        return new WarningResponse().id(id).owner(owner).reason(reason).createdAt(1_000L);
    }
}
