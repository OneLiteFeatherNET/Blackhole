package net.onelitefeather.blackhole.backend.database.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;

import java.util.List;
import java.util.Map;

@Serdeable
@Entity
@Table(name = "punishment_profiles", indexes = @Index(columnList = "owner"))
public class PunishmentProfileEntity {

    @Id
    private String owner;

    @OneToOne
    private PunishmentEntity activeChatBan;

    @OneToOne
    private PunishmentEntity activeBan;

    @OneToMany
    private List<PunishmentEntity> history;

    @ElementCollection
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @CollectionTable(name = "punishment_profiles_metadata", joinColumns = @JoinColumn(name = "owner"))
    private Map<String, Object> metaData;

    public PunishmentProfileEntity() {
    }

    public PunishmentProfileEntity(
            String owner,
            PunishmentEntity activeChatBan,
            PunishmentEntity activeBan,
            List<PunishmentEntity> history,
            Map<String, Object> metaData
    ) {
        this.owner = owner;
        this.activeChatBan = activeChatBan;
        this.activeBan = activeBan;
        this.history = history;
        this.metaData = metaData;
    }

    public String getOwner() {
        return owner;
    }

    public PunishmentEntity getActiveBan() {
        return activeBan;
    }

    public PunishmentEntity getActiveChatBan() {
        return activeChatBan;
    }

    public List<PunishmentEntity> getHistory() {
        return history;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }
}
