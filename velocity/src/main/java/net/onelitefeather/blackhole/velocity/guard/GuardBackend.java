package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.AreaBanCreateRequest;
import net.onelitefeather.blackhole.client.model.AreaBanResponse;
import net.onelitefeather.blackhole.client.model.GuardPresenceResponse;
import net.onelitefeather.blackhole.client.model.GuardStateResponse;

import java.util.List;
import java.util.UUID;

/**
 * The guard and area ban calls the proxy makes. Blocking; callers run them off the server thread.
 * The heartbeat uses {@link #presence}; the staff commands use the rest.
 */
public interface GuardBackend {

    /**
     * @return the manual level, the automatic level and the one in force
     * @throws ApiException if the backend did not answer
     */
    GuardStateResponse state() throws ApiException;

    /**
     * @param proxyId     this proxy's configured id
     * @param staffOnline how many online players hold the staff permission node
     * @return the staff presence and the level in force
     * @throws ApiException if the backend refused or did not answer
     */
    GuardPresenceResponse presence(String proxyId, int staffOnline) throws ApiException;

    /**
     * @param onBehalfOf the Minecraft UUID of the staff member who makes the change
     * @param level      OFF, STRICT or LOCKDOWN
     * @return the state after the change
     * @throws ApiException if the backend refused or did not answer
     */
    GuardStateResponse setLevel(UUID onBehalfOf, String level) throws ApiException;

    /**
     * @param onBehalfOf the Minecraft UUID of the staff member who makes the ban
     * @param request    the area ban to create
     * @return the ban as stored
     * @throws ApiException if the backend refused or did not answer
     */
    AreaBanResponse createAreaBan(UUID onBehalfOf, AreaBanCreateRequest request) throws ApiException;

    /**
     * @return the area bans that apply now
     * @throws ApiException if the backend did not answer
     */
    List<AreaBanResponse> activeAreaBans() throws ApiException;

    /**
     * @param onBehalfOf the Minecraft UUID of the staff member who lifts the ban
     * @param id         the ban's id
     * @param reason     why it is lifted
     * @return the ban after the lift
     * @throws ApiException if the backend refused or did not answer
     */
    AreaBanResponse liftAreaBan(UUID onBehalfOf, String id, String reason) throws ApiException;
}
