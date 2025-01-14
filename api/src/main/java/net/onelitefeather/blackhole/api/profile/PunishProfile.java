package net.onelitefeather.blackhole.api.profile;

import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.phoca.metadata.Metadata;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The interface defines the basic structure to describe a profile in the punishment system.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public sealed interface PunishProfile extends Metadata permits PunishProfileDTO {

    Comparator<PunishEntry> DEFAULT_COMPARATOR = Comparator.comparingLong(PunishEntry::creationDate);

    @Contract(pure = true)
    static @NotNull Builder builder() {
        return new PunishProfileBuilder();
    }

    @Contract(value = "_ -> new", pure = true)
    static @NotNull Builder builder(@NotNull PunishProfile profile) {
        return new PunishProfileBuilder(profile);
    }

    /**
     * Returns the owner of the profile.
     * The owner is stored in an SHA-512 hash.
     *
     * @return the profile owner
     */
    @NotNull String owner();

    /**
     * Returns the active chat ban wrapped in an @{link Optional}.
     *
     * @return the active chat ban
     */
    @ApiStatus.Experimental
    @NotNull Optional<@Nullable PunishEntry> activeChatBan();

    /**
     * Returns the active ban wrapped in an @{link Optional}.
     *
     * @return the active ban
     */
    @NotNull Optional<@Nullable PunishEntry> activeBan();

    /**
     * Returns the active mute wrapped in an @{link Optional}.
     *
     * @return the active mute
     */
    default @NotNull List<PunishEntry> historyByType(@NotNull PunishType type) {
        return this.historyByType(type, DEFAULT_COMPARATOR);
    }

    /**
     * Returns the punishment history of the profile by the given type.
     *
     * @param type       the type of the punishment
     * @param comparator the comparator to sort the list
     * @return the given history
     */
    @NotNull List<PunishEntry> historyByType(@NotNull PunishType type, @NotNull Comparator<PunishEntry> comparator);

    /**
     * Returns the punishment history of the profile.
     *
     * @return the given history
     */
    default List<PunishEntry> history() {
        return this.history(DEFAULT_COMPARATOR);
    }

    /**
     * Returns the punishment history of the profile.
     *
     * @return the given history
     */
    @NotNull
    @UnmodifiableView
    List<PunishEntry> history(@NotNull Comparator<PunishEntry> comparator);

    sealed interface Builder permits PunishProfileBuilder {

        /**
         * Sets the owner of the profile.
         *
         * @param owner the owner of the profile
         * @return the builder instance
         */
        @NotNull Builder owner(@NotNull String owner);

        /**
         * Sets the active chat ban of the profile.
         *
         * @param activeChatBan the active chat ban
         * @return the builder instance
         */
        @NotNull Builder activeChatBan(@Nullable PunishEntry activeChatBan);

        /**
         * Sets the active ban of the profile.
         *
         * @param activeBan the active ban
         * @return the builder instance
         */
        @NotNull Builder activeBan(@Nullable PunishEntry activeBan);
        /**
         * Sets the metadata of the profile.
         *
         * @param metaData the metadata of the profile
         * @return the builder instance
         */
        @NotNull Builder metaData(@NotNull Map<String, Object> metaData);

        /**
         * Builds a new instance of the {@link PunishProfile}.
         *
         * @return the created instance
         */
        @NotNull PunishProfile build();
    }
}
