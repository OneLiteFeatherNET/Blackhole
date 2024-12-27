package net.onelitefeather.blackhole.api.punish;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.utils.IdGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PunishEntryBuilder implements PunishEntry.Builder {

    private final String identifier;
    private PunishType type;
    private UUID source;
    private final Map<String, Object> metaData = new HashMap<>();

    public PunishEntryBuilder() {
        this.identifier = IdGenerator.generateId();
        // Empty constructor
    }

    public PunishEntryBuilder(@NotNull PunishEntry punishEntry) {
        this.identifier = punishEntry.identifier();
        this.type = punishEntry.type();
        this.source = punishEntry.source();
        this.metaData.putAll(punishEntry.metaData());
    }

    @Override
    public PunishEntry.@NotNull Builder type(@NotNull PunishType type) {
        this.type = type;
        return this;
    }

    @Override
    public PunishEntry.@NotNull Builder source(@NotNull UUID source) {
        this.source = source;
        return this;
    }

    @Override
    public @NotNull PunishEntry build() {
        if (this.type == null) {
            throw new IllegalStateException("The ban type must be set");
        }

        this.metaData.computeIfAbsent(Metadata.META_DATA_KEY_CREATION_DATE, key -> System.currentTimeMillis());
        this.metaData.put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());

        return new PunishEntryDTO(this.identifier, this.type, this.source, this.metaData);
    }
}
