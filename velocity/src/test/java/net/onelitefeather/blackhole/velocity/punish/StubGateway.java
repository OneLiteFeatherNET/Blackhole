package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkLookupResponse;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A backend the test controls: what it answers is set per test, what was asked is recorded.
 */
final class StubGateway implements BackendGateway {

    List<PunishTemplateResponse> templates = new ArrayList<>();
    ApiException templatesFailure;
    PunishmentApplyResponse applyResponse = new PunishmentApplyResponse();
    ApiException applyFailure;
    PunishmentRevokeResponse revokeResponse = new PunishmentRevokeResponse();
    ApiException revokeFailure;
    Optional<PunishProfileDTO> profile = Optional.empty();
    ApiException profileFailure;
    EloProfileResponse elo = new EloProfileResponse();
    StaffLinkConfirmResponse confirmResponse = new StaffLinkConfirmResponse();
    ApiException confirmFailure;
    StaffLinkLookupResponse lookupResponse = new StaffLinkLookupResponse().linked(false);
    ApiException lookupFailure;
    /** The player and code of each confirm call. */
    final List<String> confirmed = new ArrayList<>();

    final List<String> applied = new ArrayList<>();
    final List<String> revoked = new ArrayList<>();
    /** The correlation id each apply call carried, null where none. */
    final List<String> appliedCorrelationIds = new ArrayList<>();
    /** The owner and correlation id of each evidence post, in the order the calls were made. */
    final List<String> postedEvidence = new ArrayList<>();
    final List<ChatSnapshot> postedSnapshots = new ArrayList<>();
    /** The punishments whose evidence was counted. */
    final List<String> countedPunishments = new ArrayList<>();
    /** What the count call answers. */
    CompletableFuture<Long> countResult = CompletableFuture.completedFuture(0L);
    /** The order of apply and evidence calls. */
    final List<String> calls = new ArrayList<>();
    /** What the evidence post answers; completes successfully unless the test replaces it. */
    CompletableFuture<Void> evidenceResult = CompletableFuture.completedFuture(null);
    /** Thrown by the evidence post itself, as a broken client would. */
    RuntimeException evidenceFailure;

    @Override
    public List<PunishTemplateResponse> templates() throws ApiException {
        if (this.templatesFailure != null) {
            throw this.templatesFailure;
        }
        return this.templates;
    }

    @Override
    public PunishmentApplyResponse apply(String owner, String templateKey, UUID source, String evidenceCorrelationId) throws ApiException {
        this.applied.add(templateKey);
        this.appliedCorrelationIds.add(evidenceCorrelationId);
        this.calls.add("apply");
        if (this.applyFailure != null) {
            throw this.applyFailure;
        }
        return this.applyResponse;
    }

    @Override
    public CompletableFuture<Void> postChatEvidence(String owner, String correlationId, ChatSnapshot snapshot, String sourceSystem, UUID source) {
        this.calls.add("evidence");
        this.postedEvidence.add(owner + "/" + correlationId + "/" + sourceSystem);
        this.postedSnapshots.add(snapshot);
        if (this.evidenceFailure != null) {
            throw this.evidenceFailure;
        }
        return this.evidenceResult;
    }

    @Override
    public CompletableFuture<Long> countEvidence(String punishment) {
        this.calls.add("count");
        this.countedPunishments.add(punishment);
        return this.countResult;
    }

    @Override
    public PunishmentRevokeResponse revoke(String owner, String section, UUID source) throws ApiException {
        this.revoked.add(section);
        if (this.revokeFailure != null) {
            throw this.revokeFailure;
        }
        return this.revokeResponse;
    }

    @Override
    public Optional<PunishProfileDTO> profile(String owner) throws ApiException {
        if (this.profileFailure != null) {
            throw this.profileFailure;
        }
        return this.profile;
    }

    @Override
    public EloProfileResponse elo(String owner) {
        return this.elo;
    }

    @Override
    public StaffLinkConfirmResponse confirmStaffLink(String code, UUID player) throws ApiException {
        this.confirmed.add(player + "/" + code);
        if (this.confirmFailure != null) {
            throw this.confirmFailure;
        }
        return this.confirmResponse;
    }

    @Override
    public StaffLinkLookupResponse lookupStaffLink(UUID player) throws ApiException {
        if (this.lookupFailure != null) {
            throw this.lookupFailure;
        }
        return this.lookupResponse;
    }

    static PunishTemplateResponse template(String key, String section) {
        return new PunishTemplateResponse().identifier(UUID.randomUUID()).key(key).reason("reason of " + key).section(section);
    }
}
