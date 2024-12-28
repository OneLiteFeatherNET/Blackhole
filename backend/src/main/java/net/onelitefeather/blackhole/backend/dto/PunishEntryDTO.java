package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.blackhole.api.metadata.Expirable;
import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.blackhole.api.template.PunishTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a punish entry which can be used to store the punishment in the database.
 *
 * @param identifier   the identifier of the punishment
 * @param source       the source of the punishment
 * @param template     the template of the punishment
 * @param metaData     the metadata of the punishment
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
@Serdeable
@ReflectiveAccess
public record PunishEntryDTO(
    String identifier,
    UUID source,
    PunishTemplateDTO template,
    Map<String, Object> metaData
) implements Metadata, Expirable {

    @Override
    public void addMetaData( String key, Object value) {
        this.metaData.put(key, value);
    }

    @Override
    public void removeMetaData( String key) {
        this.metaData.remove(key);
    }

    @Override
    public boolean hasMetaData( String key) {
        return this.metaData.containsKey(key);
    }

    @Override
    public Optional< Object> getMetaData( String key) {
        return Optional.ofNullable(this.metaData.get(key));
    }

    @Override
    public long creationDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_CREATION_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long updateDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_UPDATE_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long expirationDate() {
        return Optional.ofNullable(this.metaData.get(Expirable.META_DATA_KEY_EXPIRATION_DATE)).map(Long.class::cast).orElseThrow();
    }
}
