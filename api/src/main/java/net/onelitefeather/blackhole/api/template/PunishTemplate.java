package net.onelitefeather.blackhole.api.template;

import net.onelitefeather.blackhole.api.metadata.Durationable;
import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.punish.PunishType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.UUID;
import java.util.Map;

/**
 * The interface defines the basic structure to describe a template in the punishment system.
 * The template contains the type of the punishment and the creation date.
 *
 * @since 1.0.0
 * @version 1.0.0
 * @see PunishType
 * @see Metadata
 * @author TheMeinerLP
 */
public sealed interface PunishTemplate extends Metadata, Durationable permits PunishTemplateDTO {

    String META_DATA_KEY_TRANSLATABLE = "translatable";

    /**
     * The builder to create a new {@link PunishTemplate}.
     *
     * @return a new builder
     */
    @Contract(pure = true)
    static @NotNull Builder builder() {
        return new PunishTemplateBuilder();
    }

    @Contract(value = "_ -> new", pure = true)
    static @NotNull Builder builder(@NotNull PunishTemplate punishTemplate) {
        return new PunishTemplateBuilder(punishTemplate);
    }

    @Contract(value = "_ -> new", pure = true)
    static @NotNull Builder builder( @NotNull Map<String, Object> metaData) {
        return new PunishTemplateBuilder(metaData);
    }

    /**
     * The type of the punishment.
     *
     * @return the type of the punishment
     */
    @NotNull PunishType type();

    /**
     * The identifier of the punishment.
     *
     * @return the identifier
     */
    @NotNull UUID identifier();

    /**
     * If the reason is a key for a translation in the language file.
     *
     * @return {@code true} if the reason is translatable
     */
    boolean translatable();

    /**
     * The reason of the punishment or the key for the translation.
     *
     * @return the reason of the punishment or the key for the translation
     */
    @NotNull String reason();

    /**
     * The builder to create a new {@link PunishTemplate}.
     *
     */
    sealed interface Builder permits PunishTemplateBuilder {


        /**
         * Sets the type of the punishment.
         *
         * @param type the type of the punishment
         * @return the builder
         */
        Builder type(@NotNull PunishType type);

        /**
         * Sets the identifier of the punishment.
         *
         * @param identifier the identifier of the punishment
         * @return the builder
         */
        Builder identifier(@NotNull UUID identifier);

        /**
         * Generates a new identifier for the punishment.
         *
         * @return the builder
         */
        Builder generateIdentifier();

        /**
         * Sets if the reason is translatable.
         *
         * @return the builder
         */
        Builder translatable();

        /**
         * Sets the duration of the punishment.
         *
         * @param duration the duration of the punishment
         * @return the builder
         */
        Builder duration(Duration duration);

        /**
         * Sets the reason of the punishment.
         *
         * @param reason the reason of the punishment
         * @return the builder
         */
        Builder reason(@NotNull String reason);

        /**
         * Builds the punishment template.
         *
         * @return the punishment template
         */
        PunishTemplate build();

    }
}
