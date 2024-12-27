package net.onelitefeather.blackhole.backend.database.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.api.punish.PunishType;

import java.util.Map;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishment_templates", indexes = @Index(columnList = "identifier"))
public class PunishmentTemplateEntity {

    @Id
    private UUID identifier;

    private String reason;

    private PunishType type;

    @ElementCollection
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @CollectionTable(name = "punishment_templates_metadata", joinColumns = @JoinColumn(name = "identifier"))
    private Map<String, Object> metaData;

    public PunishmentTemplateEntity() {
    }

    public PunishmentTemplateEntity(UUID identifier, String reason, PunishType type, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.reason = reason;
        this.type = type;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public String getReason() {
        return reason;
    }

    public PunishType getType() {
        return type;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }
}
