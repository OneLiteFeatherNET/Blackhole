package net.onelitefeather.blackhole.api.template;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.punish.PunishType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class PunishTemplateBuilder implements PunishTemplate.Builder {

    private PunishType type;
    private UUID identifier;
    private String reason;
    private final Map<String, Object> metaData = new HashMap<>();

    public PunishTemplateBuilder(@NotNull PunishTemplate punishTemplate) {
        this.type = punishTemplate.type();
        this.identifier = punishTemplate.identifier();
        this.reason = punishTemplate.reason();
        this.metaData.putAll(punishTemplate.metaData());
    }

    public PunishTemplateBuilder(@NotNull Map<String, Object> metaData) {
        this.metaData.putAll(metaData);
    }

    public PunishTemplateBuilder() {
        // Empty constructor
    }

    @Override
    public PunishTemplate.Builder type(@NotNull PunishType type) {
        this.type = type;
        return this;
    }

    @Override
    public PunishTemplate.Builder identifier(@NotNull UUID identifier) {
        this.identifier = identifier;
        return this;
    }

    @Override
    public PunishTemplate.Builder generateIdentifier() {
        this.identifier = UUID.randomUUID();
        return this;
    }

    @Override
    public PunishTemplate.Builder translatable() {
        this.metaData.put(PunishTemplate.META_DATA_KEY_TRANSLATABLE, true);
        return this;
    }

    @Override
    public PunishTemplate.Builder reason(@NotNull String reason) {
        this.reason = reason;
        return this;
    }

    @Override
    public PunishTemplate build() {
        if (this.type == null) {
            throw new IllegalStateException("The type must be set");
        }
        if (this.identifier == null) {
            throw new IllegalStateException("The identifier must be set");
        }
        if (this.reason == null) {
            throw new IllegalStateException("The reason must be set");
        }
        this.metaData.computeIfAbsent(Metadata.META_DATA_KEY_CREATION_DATE, key -> System.currentTimeMillis());
        this.metaData.put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());
        return new PunishTemplateDTO(this.metaData, this.reason, this.type, this.identifier);
    }
}
