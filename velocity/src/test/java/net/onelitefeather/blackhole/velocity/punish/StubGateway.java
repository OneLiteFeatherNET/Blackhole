package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    final List<String> applied = new ArrayList<>();
    final List<String> revoked = new ArrayList<>();

    @Override
    public List<PunishTemplateResponse> templates() throws ApiException {
        if (this.templatesFailure != null) {
            throw this.templatesFailure;
        }
        return this.templates;
    }

    @Override
    public PunishmentApplyResponse apply(String owner, String templateKey, UUID source) throws ApiException {
        this.applied.add(templateKey);
        if (this.applyFailure != null) {
            throw this.applyFailure;
        }
        return this.applyResponse;
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

    static PunishTemplateResponse template(String key, String section) {
        return new PunishTemplateResponse().identifier(UUID.randomUUID()).key(key).reason("reason of " + key).section(section);
    }
}
