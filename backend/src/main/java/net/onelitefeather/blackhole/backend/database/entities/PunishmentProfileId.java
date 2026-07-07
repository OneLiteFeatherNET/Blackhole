package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.core.annotation.Introspected;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link PunishmentProfileEntity}. A player's punishment profile
 * is scoped to exactly one tenant, so the owner hash alone is no longer a unique identifier
 * across the whole database.
 *
 * <p>Kept as a plain mutable class (not a record) because JPA's {@code @IdClass} mechanism
 * populates instances via reflection over a no-arg constructor plus field access.
 * {@code @Introspected} is required so Micronaut Data can build/bind instances of this class
 * at runtime without full reflection.</p>
 */
@Introspected
public class PunishmentProfileId implements Serializable {

    private UUID tenantId;
    private String owner;

    public PunishmentProfileId() {
        // Required by JPA
    }

    public PunishmentProfileId(UUID tenantId, String owner) {
        this.tenantId = tenantId;
        this.owner = owner;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getOwner() {
        return owner;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PunishmentProfileId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(owner, that.owner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, owner);
    }
}
