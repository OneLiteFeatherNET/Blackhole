package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.api.EvidenceApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.api.PunishmentApi;
import net.onelitefeather.blackhole.client.api.PunishmentTemplatesApi;
import net.onelitefeather.blackhole.client.api.ReportApi;
import net.onelitefeather.blackhole.client.api.DiscordLinkApi;
import net.onelitefeather.blackhole.client.api.StaffLinkApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordLinkCodeResponse;
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
import net.onelitefeather.blackhole.velocity.redis.CachedTemplates;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link BackendGateway} over the generated API client; templates come from the shared cache when
 * it holds a list.
 */
public final class HttpBackendGateway implements BackendGateway {

    private final PunishmentApi punishmentApi;
    private final PunishmentTemplatesApi templatesApi;
    private final PunishProfileApi profileApi;
    private final EloApi eloApi;
    private final EvidenceApi evidenceApi;
    private final StaffLinkApi staffLinkApi;
    private final ReportApi reportApi;
    private final DiscordLinkApi discordLinkApi;
    private final CachedTemplates cachedTemplates;
    private final Clock clock;

    public HttpBackendGateway(ApiClient client, CachedTemplates cachedTemplates, Clock clock) {
        this.punishmentApi = new PunishmentApi(client);
        this.templatesApi = new PunishmentTemplatesApi(client);
        this.profileApi = new PunishProfileApi(client);
        this.eloApi = new EloApi(client);
        this.evidenceApi = new EvidenceApi(client);
        this.staffLinkApi = new StaffLinkApi(client);
        this.reportApi = new ReportApi(client);
        this.discordLinkApi = new DiscordLinkApi(client);
        this.cachedTemplates = cachedTemplates;
        this.clock = clock;
    }

    @Override
    public List<PunishTemplateResponse> templates() throws ApiException {
        Optional<List<PunishTemplateResponse>> cached = this.cachedTemplates.read();
        if (cached.isPresent()) {
            return cached.get();
        }
        PunishTemplatePage page = this.templatesApi.getAllTemplates(Pageable.builder().build());
        return page.getContent();
    }

    @Override
    public PunishmentApplyResponse apply(String owner, String templateKey, UUID source, String evidenceCorrelationId) throws ApiException {
        return this.punishmentApi.applyPunishment(new PunishmentApplyRequest()
                .owner(owner).templateKey(templateKey).onBehalfOf(source).evidenceCorrelationId(evidenceCorrelationId));
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
}
