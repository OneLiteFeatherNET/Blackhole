package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.api.EvidenceApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.api.PunishmentApi;
import net.onelitefeather.blackhole.client.api.PunishmentTemplatesApi;
import net.onelitefeather.blackhole.client.api.StaffLinkApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.EvidenceCreateRequest;
import net.onelitefeather.blackhole.client.model.Pageable;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyRequest;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeRequest;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmRequest;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkLookupResponse;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidenceRequests;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshot;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;

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
    private final RedisSyncService redis;
    private final Clock clock;

    public HttpBackendGateway(ApiClient client, RedisSyncService redis, Clock clock) {
        this.punishmentApi = new PunishmentApi(client);
        this.templatesApi = new PunishmentTemplatesApi(client);
        this.profileApi = new PunishProfileApi(client);
        this.eloApi = new EloApi(client);
        this.evidenceApi = new EvidenceApi(client);
        this.staffLinkApi = new StaffLinkApi(client);
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public List<PunishTemplateResponse> templates() throws ApiException {
        Optional<List<PunishTemplateResponse>> cached = this.redis.readCachedTemplates();
        if (cached.isPresent()) {
            return cached.get();
        }
        return this.templatesApi.getAllTemplates(Pageable.builder().build());
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
}
