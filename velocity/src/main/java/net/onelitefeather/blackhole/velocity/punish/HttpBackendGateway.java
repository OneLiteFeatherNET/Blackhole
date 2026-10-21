package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.api.PunishmentApi;
import net.onelitefeather.blackhole.client.api.PunishmentTemplatesApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.Pageable;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyRequest;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeRequest;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BackendGateway} over the generated API client; templates come from the shared cache when
 * it holds a list.
 */
public final class HttpBackendGateway implements BackendGateway {

    private final PunishmentApi punishmentApi;
    private final PunishmentTemplatesApi templatesApi;
    private final PunishProfileApi profileApi;
    private final EloApi eloApi;
    private final RedisSyncService redis;

    public HttpBackendGateway(ApiClient client, RedisSyncService redis) {
        this.punishmentApi = new PunishmentApi(client);
        this.templatesApi = new PunishmentTemplatesApi(client);
        this.profileApi = new PunishProfileApi(client);
        this.eloApi = new EloApi(client);
        this.redis = redis;
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
    public PunishmentApplyResponse apply(String owner, String templateKey, UUID source) throws ApiException {
        return this.punishmentApi.applyPunishment(new PunishmentApplyRequest().owner(owner).templateKey(templateKey).source(source));
    }

    @Override
    public PunishmentRevokeResponse revoke(String owner, String section, UUID source) throws ApiException {
        return this.punishmentApi.revokePunishment(new PunishmentRevokeRequest().owner(owner).section(section).source(source));
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
}
