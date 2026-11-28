package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.NameHistoryOwner;
import net.onelitefeather.blackhole.client.model.NameHistoryPage;
import net.onelitefeather.blackhole.client.model.IntelPolicy;
import net.onelitefeather.blackhole.client.model.LoginCheckResponse;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.client.model.ReportFeedbackResponse;
import net.onelitefeather.blackhole.client.model.WarningResponse;
import net.onelitefeather.blackhole.client.model.DiscordLinkCodeResponse;
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
public final class StubGateway implements BackendGateway {

    List<PunishTemplateResponse> templates = new ArrayList<>();
    ApiException templatesFailure;
    PunishmentApplyResponse applyResponse = new PunishmentApplyResponse();
    ApiException applyFailure;
    PunishmentRevokeResponse revokeResponse = new PunishmentRevokeResponse();
    ApiException revokeFailure;
    Optional<PunishProfileDTO> profile = Optional.empty();
    ApiException profileFailure;
    EloProfileResponse elo = new EloProfileResponse();
    final java.util.List<String> discordOwners = new java.util.ArrayList<>();
    DiscordLinkCodeResponse discordResponse = new DiscordLinkCodeResponse().code("ABCD-EFGH").expiresAt(0L);
    ApiException discordFailure;
    StaffLinkConfirmResponse confirmResponse = new StaffLinkConfirmResponse();
    ApiException confirmFailure;
    StaffLinkLookupResponse lookupResponse = new StaffLinkLookupResponse().linked(false);
    ApiException lookupFailure;
    /** A relay state report as the backend received it. */
    record RelayReport(String proxyId, boolean present, String version) {
    }

    /** The relay reports the backend accepted, in order. */
    final List<RelayReport> relayReports = new ArrayList<>();
    /** What the next relay report fails with, null for success. */
    ApiException relayReportFailure;
    /** The player and code of each confirm call. */
    final List<String> confirmed = new ArrayList<>();

    /** A login check as the backend received it. */
    public record LoginCheck(String owner, String ip) {
    }

    /** The login checks the backend received, in order. */
    public final List<LoginCheck> loginChecks = new ArrayList<>();
    /** What the login check answers; a future that never completes models a backend that does not answer. */
    public CompletableFuture<LoginCheckResponse> loginCheckAnswer = CompletableFuture.completedFuture(new LoginCheckResponse().verdict(IntelPolicy.ALLOW));

    final List<String> applied = new ArrayList<>();
    /** The player UUID each apply call carried, null where unknown. */
    final List<UUID> appliedPlayers = new ArrayList<>();
    /** The warning-only flag of each apply call, in order. */
    final List<Boolean> warningOnlyFlags = new ArrayList<>();
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
    /** The punishments whose Ban ID was requested. */
    final List<String> banIdPunishments = new ArrayList<>();
    /** What the Ban ID call answers. */
    CompletableFuture<String> banIdResult = CompletableFuture.completedFuture("7K3M9QXA");
    /** The order of apply and evidence calls. */
    final List<String> calls = new ArrayList<>();
    /** What the evidence post answers; completes successfully unless the test replaces it. */
    CompletableFuture<Void> evidenceResult = CompletableFuture.completedFuture(null);
    /** Thrown by the evidence post itself, as a broken client would. */
    RuntimeException evidenceFailure;

    /** What each report evidence post answers; completes with {@link #reportEvidenceId} unless replaced. */
    CompletableFuture<UUID> reportEvidenceResult;
    UUID reportEvidenceId = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    final List<ChatSnapshot> reportSnapshots = new ArrayList<>();
    final List<String> reportEvidenceOwners = new ArrayList<>();
    final List<String> reportEvidenceCorrelationIds = new ArrayList<>();
    ReportDTO reportResponse = new ReportDTO().identifier(UUID.fromString("00000000-0000-4000-8000-0000000000a1"));
    ApiException reportFailure;
    /** The reporter, reported, category, description and evidence of each submitted report. */
    final List<String> reports = new ArrayList<>();
    final List<java.util.Map<String, Object>> reportMetaData = new ArrayList<>();

    /** What the feedback fetch answers, and the owners it was asked for. */
    List<ReportFeedbackResponse> feedback = new ArrayList<>();
    ApiException feedbackFailure;
    ApiException acknowledgeFailure;
    final List<String> feedbackOwners = new ArrayList<>();
    final List<UUID> acknowledged = new ArrayList<>();

    /** What the single-report lookup answers, and the identifiers it was asked for. */
    ReportDTO lookedUpReport;
    ApiException lookupReportFailure;
    final List<UUID> lookedUp = new ArrayList<>();

    @Override
    public Optional<TargetResolver.Known> resolvePlayer(String name) {
        return Optional.empty();
    }

    @Override
    public NameHistoryPage nameHistory(String owner, int size) {
        return new NameHistoryPage();
    }

    @Override
    public List<NameHistoryOwner> nameOwners(String name) {
        return List.of();
    }

    @Override
    public ReportDTO report(UUID identifier) throws ApiException {
        this.lookedUp.add(identifier);
        if (this.lookupReportFailure != null) {
            throw this.lookupReportFailure;
        }
        return this.lookedUpReport;
    }

    @Override
    public List<ReportFeedbackResponse> reportFeedback(String owner) throws ApiException {
        this.feedbackOwners.add(owner);
        if (this.feedbackFailure != null) {
            throw this.feedbackFailure;
        }
        return this.feedback;
    }

    @Override
    public void acknowledgeReportFeedback(UUID identifier) throws ApiException {
        this.acknowledged.add(identifier);
        if (this.acknowledgeFailure != null) {
            throw this.acknowledgeFailure;
        }
    }

    @Override
    public CompletableFuture<UUID> postReportChatEvidence(String reported, String correlationId, ChatSnapshot snapshot, String sourceSystem) {
        this.calls.add("report-evidence");
        this.reportSnapshots.add(snapshot);
        this.reportEvidenceOwners.add(reported);
        this.reportEvidenceCorrelationIds.add(correlationId);
        return this.reportEvidenceResult != null ? this.reportEvidenceResult : CompletableFuture.completedFuture(this.reportEvidenceId);
    }

    @Override
    public ReportDTO submitReport(String reporter, String reported, ReportCategory category, String description, List<UUID> evidence, java.util.Map<String, Object> metaData) throws ApiException {
        this.calls.add("report");
        this.reportMetaData.add(metaData);
        this.reports.add(reporter + "|" + reported + "|" + category + "|" + description + "|" + evidence);
        if (this.reportFailure != null) {
            throw this.reportFailure;
        }
        return this.reportResponse;
    }

    @Override
    public List<PunishTemplateResponse> templates() throws ApiException {
        if (this.templatesFailure != null) {
            throw this.templatesFailure;
        }
        return this.templates;
    }

    @Override
    public PunishmentApplyResponse apply(String owner, UUID playerUuid, String templateKey, UUID source, String evidenceCorrelationId, boolean warningOnly) throws ApiException {
        this.appliedPlayers.add(playerUuid);
        this.applied.add(templateKey);
        this.warningOnlyFlags.add(warningOnly);
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
    public CompletableFuture<String> revealBanId(String punishment) {
        this.calls.add("ban-id");
        this.banIdPunishments.add(punishment);
        return this.banIdResult;
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

    @Override
    public DiscordLinkCodeResponse issueDiscordLinkCode(String owner) throws ApiException {
        this.discordOwners.add(owner);
        if (this.discordFailure != null) {
            throw this.discordFailure;
        }
        return this.discordResponse;
    }

    @Override
    public CompletableFuture<LoginCheckResponse> loginCheck(String owner, String ip) {
        this.loginChecks.add(new LoginCheck(owner, ip));
        return this.loginCheckAnswer;
    }

    @Override
    public WarningResponse warning(String id) {
        throw new UnsupportedOperationException("warning");
    }

    @Override
    public List<WarningResponse> pendingWarnings(String owner) {
        throw new UnsupportedOperationException("pendingWarnings");
    }

    @Override
    public void acknowledgeWarning(String id, String owner) {
        throw new UnsupportedOperationException("acknowledgeWarning");
    }

    @Override
    public net.onelitefeather.blackhole.client.model.KickAccepted kick(String idempotencyKey, String owner,
            net.onelitefeather.blackhole.client.model.KickScope scope, String target, String reason) {
        throw new UnsupportedOperationException("kick");
    }

    @Override
    public net.onelitefeather.blackhole.client.model.KickAccepted kickMass(String idempotencyKey,
            net.onelitefeather.blackhole.client.model.KickScope scope, String target, String reason) {
        throw new UnsupportedOperationException("kickMass");
    }

    @Override
    public net.onelitefeather.blackhole.client.model.KickResponse kickStatus(String id) {
        throw new UnsupportedOperationException("kickStatus");
    }

    @Override
    public void acknowledgeKick(String id, String proxyId, boolean found, boolean moved) {
        throw new UnsupportedOperationException("acknowledgeKick");
    }

    @Override
    public void reportProxyCapabilities(String proxyId, boolean present, String version) throws ApiException {
        if (this.relayReportFailure != null) {
            throw this.relayReportFailure;
        }
        this.relayReports.add(new RelayReport(proxyId, present, version));
    }

    static PunishTemplateResponse template(String key, String section) {
        return new PunishTemplateResponse().identifier(UUID.randomUUID()).key(key).reason("reason of " + key).section(section);
    }
}
