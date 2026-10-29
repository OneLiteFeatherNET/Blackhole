package net.onelitefeather.blackhole.backend.exemption;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * An automatic punishment held back because the player is exempt. Its state moves {@code OPEN} to {@code APPLIED} or
 * {@code DISMISSED} once, by an admin. Deliberately not {@code @Serdeable}: entities never leave the backend.
 */
@Entity
@Table(name = "exemption_review")
public class ExemptionReviewEntity {

    @Id
    private UUID id;

    @Column(name = "owner")
    private String owner;

    @Column(name = "section_key")
    private String sectionKey;

    /** {@code SOFT} for the temporary punishment, {@code PERMANENT} for the permanent one. */
    @Column(name = "tier")
    private String tier;

    @Column(name = "score_after")
    private int scoreAfter;

    @Column(name = "crossed_at")
    private long crossedAt;

    @Column(name = "state")
    private String state;

    @Column(name = "decided_by_oid")
    private UUID decidedByOid;

    @Column(name = "decided_at")
    private Long decidedAt;

    @Column(name = "justification")
    private String justification;

    public ExemptionReviewEntity() {
        // Empty constructor for JPA
    }

    public ExemptionReviewEntity(UUID id, String owner, String sectionKey, String tier, int scoreAfter, long crossedAt) {
        this.id = id;
        this.owner = owner;
        this.sectionKey = sectionKey;
        this.tier = tier;
        this.scoreAfter = scoreAfter;
        this.crossedAt = crossedAt;
        this.state = ExemptionReviewState.OPEN.name();
    }

    public UUID getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public String getTier() {
        return tier;
    }

    public int getScoreAfter() {
        return scoreAfter;
    }

    public long getCrossedAt() {
        return crossedAt;
    }

    public String getState() {
        return state;
    }

    public UUID getDecidedByOid() {
        return decidedByOid;
    }

    public Long getDecidedAt() {
        return decidedAt;
    }

    public String getJustification() {
        return justification;
    }

    /** Records the decision; the state is the only thing that changes after creation. */
    public void decide(ExemptionReviewState newState, UUID by, long at, String reason) {
        this.state = newState.name();
        this.decidedByOid = by;
        this.decidedAt = at;
        this.justification = reason;
    }
}
