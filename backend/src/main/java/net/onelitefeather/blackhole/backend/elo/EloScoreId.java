package net.onelitefeather.blackhole.backend.elo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link EloScoreEntity}: one score per hashed owner and section.
 */
@Embeddable
public class EloScoreId implements Serializable {

    @Column(name = "owner")
    private String owner;

    @Column(name = "section_key")
    private String sectionKey;

    public EloScoreId() {
        // Empty constructor for JPA
    }

    public EloScoreId(String owner, String sectionKey) {
        this.owner = owner;
        this.sectionKey = sectionKey;
    }

    public String getOwner() {
        return owner;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EloScoreId that
                && Objects.equals(this.owner, that.owner) && Objects.equals(this.sectionKey, that.sectionKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.owner, this.sectionKey);
    }
}
