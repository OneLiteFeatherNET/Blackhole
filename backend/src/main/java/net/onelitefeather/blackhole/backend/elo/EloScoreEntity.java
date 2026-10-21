package net.onelitefeather.blackhole.backend.elo;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A player's ELO score in one punishment section. A missing row means the section's base score, so
 * rows only exist for players that were ever scored.
 */
@Entity
@Table(name = "elo_scores")
public class EloScoreEntity {

    @EmbeddedId
    private EloScoreId id;

    private int score;

    private long updatedAt;

    public EloScoreEntity() {
        // Empty constructor for JPA
    }

    public EloScoreEntity(String owner, String sectionKey, int score, long updatedAt) {
        this.id = new EloScoreId(owner, sectionKey);
        this.score = score;
        this.updatedAt = updatedAt;
    }

    public EloScoreId getId() {
        return id;
    }

    public String getOwner() {
        return this.id.getOwner();
    }

    public String getSectionKey() {
        return this.id.getSectionKey();
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
