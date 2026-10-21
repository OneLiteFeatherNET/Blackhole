package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The backend calls the staff commands need, in the proxy's terms. The commands and
 * {@link PunishService} depend on this interface only, so their decisions can be tested with a stub.
 */
public interface BackendGateway {

    /**
     * @return every punishment template (from the shared cache when it holds a list, otherwise the API)
     */
    List<PunishTemplateResponse> templates() throws ApiException;

    /**
     * Applies the template with the given key to the owner.
     */
    PunishmentApplyResponse apply(String owner, String templateKey, UUID source) throws ApiException;

    /**
     * Revokes the owner's active punishment in the section.
     */
    PunishmentRevokeResponse revoke(String owner, String section, UUID source) throws ApiException;

    /**
     * @return the owner's punishment profile; empty if they have none yet
     */
    Optional<PunishProfileDTO> profile(String owner) throws ApiException;

    /**
     * @return the owner's score in every section
     */
    EloProfileResponse elo(String owner) throws ApiException;
}
