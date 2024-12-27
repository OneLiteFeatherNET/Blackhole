package net.onelitefeather.blackhole.backend.database.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.api.punish.PunishType;

import java.util.Map;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishments", indexes = @Index(columnList = "identifier"))
public class PunishmentEntity {

    @Id
    private String identifier;

    private PunishType type;

    private UUID source;

    @ElementCollection
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @CollectionTable(name = "punishments_metadata", joinColumns = @JoinColumn(name = "identifier"))
    private Map<String, Object> metaData;

    public PunishmentEntity() {
    }

    public PunishmentEntity(String identifier, PunishType type, UUID source, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.type = type;
        this.source = source;
        this.metaData = metaData;
    }

    public String getIdentifier() {
        return identifier;
    }

    public PunishType getType() {
        return type;
    }

    public UUID getSource() {
        return source;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }
}
