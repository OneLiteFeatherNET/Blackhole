package net.onelitefeather.blackhole.request.entry;

import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public sealed interface PunishWebRequests permits PunishEntryRequests {

    String ENTRY_BASE_URL = "/punishment";

    /**
     * Add a new entry to a {@link PunishProfile}.
     *
     * @param owner      the owner of the profile
     * @param templateId the template id
     * @param source     the source of the punishment
     * @return the updated profile
     */
    @NotNull PunishProfile add(@NotNull String owner, @NotNull UUID templateId, @NotNull UUID source);

    /**
     * Get all entries from the server.
     *
     * @param page     the page of the list
     * @param pageSize the size of the page
     * @return the list of entries
     */
    @NotNull List<PunishEntry> getAll(int page, int pageSize);
}
