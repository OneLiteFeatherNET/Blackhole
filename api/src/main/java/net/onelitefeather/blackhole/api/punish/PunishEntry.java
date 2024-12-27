package net.onelitefeather.blackhole.api.punish;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The interface defines the basic structure to describe an entry in the punishment system.
 * The entry contains the type of the punishment and the creation date.
 */
public sealed interface PunishEntry extends Metadata permits PunishEntryDTO {

    /**
     * Creates a new {@link PunishEntry.Builder}.
     *
     * @return a new builder
     */
    @Contract(pure = true)
    static @NotNull Builder builder() {
        return new PunishEntryBuilder();
    }

    /**
     * Creates a new {@link PunishEntry.Builder} with the given {@link PunishEntry}.
     *
     * @param punishEntry the entry to set
     * @return a new builder
     */
    @Contract(pure = true)
    static @NotNull Builder builder(@NotNull PunishEntry punishEntry) {
        return new PunishEntryBuilder(punishEntry);
    }

    /**
     * The identifier of the ban.
     *
     * @return the identifier
     */
    @NotNull String identifier();

    /**
     * The type of the ban.
     *
     * @return the type of the ban
     */
    @NotNull PunishType type();

    /**
     * The source of the ban.
     *
     * @return the source of the ban
     */
    @NotNull UUID source();


    sealed interface Builder permits PunishEntryBuilder {

        /**
         * Sets the type of the ban.
         *
         * @param type the type of the ban
         * @return the builder instance
         */
        @NotNull Builder type(@NotNull PunishType type);

        /**
         * Sets the source of the ban.
         *
         * @param source the source of the ban
         * @return the builder instance
         */
        @NotNull Builder source(@NotNull UUID source);

        /**
         * Builds the {@link PunishEntry}.
         *
         * @return the created entry
         */
        @NotNull PunishEntry build();
    }
}
