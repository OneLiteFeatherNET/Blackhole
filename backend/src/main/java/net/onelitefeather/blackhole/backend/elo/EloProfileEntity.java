package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * A player's two independent ELO tracks - a 1:1 extension of their punishment profile.
 */
@Serdeable
@Entity
@Table(name = "elo_profiles")
public class EloProfileEntity {

    @Id
    private String owner;

    private int chatElo;

    private int gameplayElo;

    private long chatEloUpdatedAt;

    private long gameplayEloUpdatedAt;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    public EloProfileEntity() {
        // Empty constructor for JPA
    }

    public EloProfileEntity(String owner, int chatElo, int gameplayElo, long chatEloUpdatedAt, long gameplayEloUpdatedAt, Map<String, Object> metaData) {
        this.owner = owner;
        this.chatElo = chatElo;
        this.gameplayElo = gameplayElo;
        this.chatEloUpdatedAt = chatEloUpdatedAt;
        this.gameplayEloUpdatedAt = gameplayEloUpdatedAt;
        this.metaData = metaData;
    }

    public String getOwner() {
        return owner;
    }

    public int getChatElo() {
        return chatElo;
    }

    public void setChatElo(int chatElo) {
        this.chatElo = chatElo;
    }

    public int getGameplayElo() {
        return gameplayElo;
    }

    public void setGameplayElo(int gameplayElo) {
        this.gameplayElo = gameplayElo;
    }

    public long getChatEloUpdatedAt() {
        return chatEloUpdatedAt;
    }

    public void setChatEloUpdatedAt(long chatEloUpdatedAt) {
        this.chatEloUpdatedAt = chatEloUpdatedAt;
    }

    public long getGameplayEloUpdatedAt() {
        return gameplayEloUpdatedAt;
    }

    public void setGameplayEloUpdatedAt(long gameplayEloUpdatedAt) {
        this.gameplayEloUpdatedAt = gameplayEloUpdatedAt;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public int getScore(EloTrack track) {
        return track == EloTrack.CHAT ? this.chatElo : this.gameplayElo;
    }

    public void setScore(EloTrack track, int score) {
        if (track == EloTrack.CHAT) {
            this.chatElo = score;
        } else {
            this.gameplayElo = score;
        }
    }

    public long getUpdatedAt(EloTrack track) {
        return track == EloTrack.CHAT ? this.chatEloUpdatedAt : this.gameplayEloUpdatedAt;
    }

    public void setUpdatedAt(EloTrack track, long updatedAt) {
        if (track == EloTrack.CHAT) {
            this.chatEloUpdatedAt = updatedAt;
        } else {
            this.gameplayEloUpdatedAt = updatedAt;
        }
    }

    public EloProfileDTO toDTO() {
        return new EloProfileDTO(this.owner, this.chatElo, this.gameplayElo, this.chatEloUpdatedAt, this.gameplayEloUpdatedAt, this.metaData);
    }
}
