package net.onelitefeather.blackhole.backend.exemption;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link PlayerExemptionEntity}: one row per hashed owner and exemption source.
 */
@Embeddable
public class PlayerExemptionId implements Serializable {

    @Column(name = "owner")
    private String owner;

    @Column(name = "source")
    private String source;

    public PlayerExemptionId() {
        // Empty constructor for JPA
    }

    public PlayerExemptionId(String owner, String source) {
        this.owner = owner;
        this.source = source;
    }

    public String getOwner() {
        return owner;
    }

    public String getSource() {
        return source;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlayerExemptionId that
                && Objects.equals(this.owner, that.owner) && Objects.equals(this.source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, source);
    }
}
