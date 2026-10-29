package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.api.EvidenceApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.api.PunishmentApi;
import net.onelitefeather.blackhole.client.api.PunishmentTemplatesApi;
import net.onelitefeather.blackhole.client.api.ReportApi;
import net.onelitefeather.blackhole.client.api.DiscordLinkApi;
import net.onelitefeather.blackhole.client.api.IntelApi;
import net.onelitefeather.blackhole.client.api.KickApi;
import net.onelitefeather.blackhole.client.api.PlayerApi;
import net.onelitefeather.blackhole.client.api.ProxyApi;
import net.onelitefeather.blackhole.client.model.ProxyCapabilityReportRequest;
import net.onelitefeather.blackhole.client.model.ProxyCapabilitySignedVelocity;
import net.onelitefeather.blackhole.client.api.StaffLinkApi;
import net.onelitefeather.blackhole.client.api.WarningApi;
import net.onelitefeather.blackhole.client.model.WarningAcknowledgeRequest;
import net.onelitefeather.blackhole.client.model.WarningResponse;
import net.onelitefeather.blackhole.client.model.KickAccepted;
import net.onelitefeather.blackhole.client.model.KickAck;
import net.onelitefeather.blackhole.client.model.KickCreateRequest;
import net.onelitefeather.blackhole.client.model.KickMassCreateRequest;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.client.model.KickScope;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordLinkCodeResponse;
import net.onelitefeather.blackhole.client.model.NameHistoryOwner;
import net.onelitefeather.blackhole.client.model.NameHistoryPage;
import net.onelitefeather.blackhole.client.model.PlayerResolveResponse;
import net.onelitefeather.blackhole.client.model.DiscordLinkPlayerCodeRequest;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.EvidenceCreateRequest;
import net.onelitefeather.blackhole.client.model.Pageable;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplatePage;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyRequest;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeRequest;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.client.model.ReportFeedbackResponse;
import net.onelitefeather.blackhole.client.model.ReportRequestDTO;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmRequest;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkLookupResponse;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidenceRequests;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshot;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link BackendGateway} over the generated API client. The template list (tab completion asks for it on
 * every keystroke) is kept for a short time, so a burst of completions is one backend call.
 */
public final class HttpBackendGateway implements BackendGateway {

    /** How long a read template list is reused. */
    static final java.time.Duration TEMPLATES_TTL = java.time.Duration.ofSeconds(30);

    private record CachedList(List<PunishTemplateResponse> templates, long readAtMillis) {
    }

    private final PunishmentApi punishmentApi;
    private final PunishmentTemplatesApi templatesApi;
    private final PunishProfileApi profileApi;
    private final EloApi eloApi;
    private final EvidenceApi evidenceApi;
    private final StaffLinkApi staffLinkApi;
    private final ReportApi reportApi;
    private final DiscordLinkApi discordLinkApi;
    private final KickApi kickApi;
    private final ProxyApi proxyApi;
    private final WarningApi warningApi;
    private final PlayerApi playerApi;
    private final IntelApi intelApi;
    private volatile CachedList cachedTemplates;
    private final Clock clock;

    public HttpBackendGateway(ApiClient client, Clock clock) {
        this.punishmentApi = new PunishmentApi(client);
        this.templatesApi = new PunishmentTemplatesApi(client);
        this.profileApi = new PunishProfileApi(client);
        this.eloApi = new EloApi(client);
        this.evidenceApi = new EvidenceApi(client);
        this.staffLinkApi = new StaffLinkApi(client);
        this.reportApi = new ReportApi(client);
        this.discordLinkApi = new DiscordLinkApi(client);
        this.kickApi = new KickApi(client);
        this.proxyApi = new ProxyApi(client);
        this.warningApi = new WarningApi(client);
        this.playerApi = new PlayerApi(client);
        this.intelApi = new IntelApi(client);
        this.clock = clock;
    }

    @Override
    public List<PunishTemplateResponse> templates() throws ApiException {
        CachedList cached = this.cachedTemplates;
        long now = this.clock.millis();
        if (cached != null && now - cached.readAtMillis() < TEMPLATES_TTL.toMillis()) {
            return cached.templates();
        }
        PunishTemplatePage page = this.templatesApi.getAllTemplates(Pageable.builder().build());
        this.cachedTemplates = new CachedList(page.getContent(), now);
        return page.getContent();
    }

    @Override
    public PunishmentApplyResponse apply(String owner, UUID playerUuid, String templateKey, UUID source, String evidenceCorrelationId, boolean warningOnly) throws ApiException {
        PunishmentApplyRequest request = new PunishmentApplyRequest()
                .owner(owner).templateKey(templateKey).onBehalfOf(source).evidenceCorrelationId(evidenceCorrelationId);
        // An ordinary apply sends no flag at all, exactly as before warnings existed.
        if (warningOnly) {
            request.warningOnly(true);
        }
        // The UUID is sent only when the plugin knows it; the backend lists nothing without one.
        if (playerUuid != null) {
            request.playerUuid(playerUuid.toString());
        }
        return this.punishmentApi.applyPunishment(request);
    }

    @Override
    public CompletableFuture<Void> postChatEvidence(
            String owner, String correlationId, ChatSnapshot snapshot, String sourceSystem, UUID source
    ) {
        EvidenceCreateRequest request = ChatEvidenceRequests.create(owner, correlationId, snapshot, sourceSystem, source, this.clock.millis());
        return CompletableFuture.runAsync(() -> {
            try {
                this.evidenceApi.attachEvidence(request);
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Long> countEvidence(String punishment) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.evidenceApi.countPunishmentEvidence(punishment).getCount();
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<String> revealBanId(String punishment) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.punishmentApi.revealBanId(punishment).getBanId();
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public PunishmentRevokeResponse revoke(String owner, String section, UUID source) throws ApiException {
        return this.punishmentApi.revokePunishment(new PunishmentRevokeRequest().owner(owner).section(section).onBehalfOf(source));
    }

    @Override
    public Optional<PunishProfileDTO> profile(String owner) throws ApiException {
        try {
            return Optional.ofNullable(this.profileApi.getById(owner));
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<TargetResolver.Known> resolvePlayer(String name) throws ApiException {
        try {
            PlayerResolveResponse resolved = this.playerApi.resolvePlayer(name);
            return Optional.of(new TargetResolver.Known(resolved.getUuid(), resolved.getName()));
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public NameHistoryPage nameHistory(String owner, int size) throws ApiException {
        return this.intelApi.listPlayerNames(owner, 0, size);
    }

    @Override
    public List<NameHistoryOwner> nameOwners(String name) throws ApiException {
        return this.intelApi.findNameOwners(name).getOwners();
    }

    @Override
    public EloProfileResponse elo(String owner) throws ApiException {
        return this.eloApi.getEloProfile(owner);
    }

    @Override
    public StaffLinkConfirmResponse confirmStaffLink(String code, UUID player) throws ApiException {
        return this.staffLinkApi.confirmStaffLink(new StaffLinkConfirmRequest().code(code).onBehalfOf(player));
    }

    @Override
    public StaffLinkLookupResponse lookupStaffLink(UUID player) throws ApiException {
        return this.staffLinkApi.lookupStaffLinkByMinecraft(player);
    }

    @Override
    public CompletableFuture<UUID> postReportChatEvidence(String reported, String correlationId, ChatSnapshot snapshot, String sourceSystem) {
        EvidenceCreateRequest request = ChatEvidenceRequests.create(reported, correlationId, snapshot, sourceSystem, null, this.clock.millis());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.evidenceApi.attachEvidence(request).getIdentifier();
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public ReportDTO submitReport(String reporter, String reported, ReportCategory category, String description, List<UUID> evidence, java.util.Map<String, Object> metaData) throws ApiException {
        return this.reportApi.submitReport(new ReportRequestDTO()
                .reporterHash(reporter).reportedHash(reported).category(category).description(description)
                .evidenceReferences(evidence).metaData(metaData));
    }

    @Override
    public ReportDTO report(UUID identifier) throws ApiException {
        return this.reportApi.getReport(identifier);
    }

    @Override
    public List<ReportFeedbackResponse> reportFeedback(String owner) throws ApiException {
        List<ReportFeedbackResponse> items = this.reportApi.getPendingReportFeedback(owner).getItems();
        return items == null ? List.of() : items;
    }

    @Override
    public void acknowledgeReportFeedback(UUID identifier) throws ApiException {
        this.reportApi.acknowledgeReportFeedback(identifier);
    }

    @Override
    public DiscordLinkCodeResponse issueDiscordLinkCode(String owner) throws ApiException {
        return this.discordLinkApi.issuePlayerDiscordLinkCode(new DiscordLinkPlayerCodeRequest().owner(owner));
    }

    @Override
    public KickAccepted kick(String idempotencyKey, String owner, KickScope scope, String target, String reason) throws ApiException {
        return this.kickApi.kickPlayer(idempotencyKey, new KickCreateRequest().owner(owner).scope(scope).target(target).reason(reason));
    }

    @Override
    public KickAccepted kickMass(String idempotencyKey, KickScope scope, String target, String reason) throws ApiException {
        return this.kickApi.kickMass(idempotencyKey, new KickMassCreateRequest().scope(scope).target(target).reason(reason));
    }

    @Override
    public KickResponse kickStatus(String id) throws ApiException {
        return this.kickApi.getKick(id);
    }

    @Override
    public void acknowledgeKick(String id, String proxyId, boolean found, boolean moved) throws ApiException {
        this.kickApi.acknowledgeKick(id, new KickAck().proxyId(proxyId).found(found).moved(moved));
    }

    @Override
    public void reportProxyCapabilities(String proxyId, boolean present, String version) throws ApiException {
        ProxyCapabilitySignedVelocity signedVelocity = new ProxyCapabilitySignedVelocity().present(present);
        if (version != null) {
            signedVelocity.version(version);
        }
        this.proxyApi.reportProxyCapabilities(new ProxyCapabilityReportRequest().proxyId(proxyId).signedVelocity(signedVelocity));
    }

    @Override
    public WarningResponse warning(String id) throws ApiException {
        return this.warningApi.getWarning(id);
    }

    @Override
    public List<WarningResponse> pendingWarnings(String owner) throws ApiException {
        List<WarningResponse> items = this.warningApi.listPendingWarnings(owner, null).getItems();
        return items == null ? List.of() : items;
    }

    @Override
    public void acknowledgeWarning(String id, String owner) throws ApiException {
        this.warningApi.acknowledgeWarning(id, new WarningAcknowledgeRequest().owner(owner));
    }
}
