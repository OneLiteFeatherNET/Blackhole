package net.onelitefeather.blackhole.api.profile;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.api.punish.PunishType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The interface defines the basic structure to describe a profile in the punishment system.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @see PunishProfileBuilder
 * @since 1.0.0
 */
public final class PunishProfileBuilder implements PunishProfile.Builder {

    private String owner;
    private PunishEntry activeChatBan;
    private PunishEntry activeBan;
    private List<PunishEntry> history = new ArrayList<>();
    private final Map<String, Object> metaData = new HashMap<>();

    /**
     * Creates a new instance of the {@link PunishProfileBuilder}.
     */
    public PunishProfileBuilder() {
    }

    /**
     * Creates a new instance of the {@link PunishProfileBuilder} with the given profile.
     *
     * @param profile the profile to set
     */
    public PunishProfileBuilder(@NotNull PunishProfile profile) {
        this.owner = profile.owner();
        this.activeChatBan = profile.activeChatBan().orElse(null);
        this.activeBan = profile.activeBan().orElse(null);
        this.history = new ArrayList<>(profile.history());
        this.metaData.putAll(profile.metaData());
    }


    @Override
    public PunishProfile.@NotNull Builder owner(@NotNull String owner) {
        this.owner = owner;
        return this;
    }

    @Override
    public PunishProfile.@NotNull Builder activeChatBan(@Nullable PunishEntry activeChatBan) {
        this.activeChatBan = activeChatBan;
        return this;
    }

    @Override
    public PunishProfile.@NotNull Builder activeBan(@Nullable PunishEntry activeBan) {
        if (activeBan != null && activeBan.type() == PunishType.CHAT) {
            throw new IllegalArgumentException("The active ban can't be a chat ban");
        }
        this.activeBan = activeBan;
        return this;
    }

    @Override
    public PunishProfile.@NotNull Builder metaData(@NotNull Map<String, Object> metaData) {
        this.metaData.putAll(metaData);
        return this;
    }

    @Override
    public @NotNull PunishProfile build() {
        this.metaData.computeIfAbsent(Metadata.META_DATA_KEY_CREATION_DATE, key -> System.currentTimeMillis());
        this.metaData.put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());

        return new PunishProfileDTO(
                this.owner,
                Optional.ofNullable(this.activeChatBan),
                Optional.ofNullable(this.activeBan),
                this.history,
                this.metaData
        );
    }
}
