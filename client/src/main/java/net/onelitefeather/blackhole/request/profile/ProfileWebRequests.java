package net.onelitefeather.blackhole.request.profile;

import net.onelitefeather.blackhole.api.profile.PunishProfile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The interface contains all web requests to interact with the ban profiles.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public sealed interface ProfileWebRequests permits PunishProfileRequests {

    String PROFILE_BASE_URL = "/profile";

    /**
     * Add a new profile to the server.
     *
     * @param profile the profile to add
     * @return the added profile
     */
    @NotNull PunishProfile add(@NotNull PunishProfile profile);

    /**
     * Get a profile from the server.
     *
     * @param owner the owner of the profile
     * @return the profile
     */
    @NotNull PunishProfile get(@NotNull String owner);

    /**
     * Update a profile on the server.
     *
     * @param owner   the owner of the profile
     * @param profile the profile to update
     * @return the updated profile
     */
    @NotNull PunishProfile update(@NotNull String owner, @NotNull PunishProfile profile);

    /**
     * Delete a profile from the server.
     *
     * @param owner the owner of the profile
     * @return the deleted profile
     */
    @NotNull PunishProfile delete(@NotNull String owner);

    /**
     * Get all profiles from the server.
     *
     * @return the list of profiles
     */
    @NotNull List<PunishProfile> getAll();
}
