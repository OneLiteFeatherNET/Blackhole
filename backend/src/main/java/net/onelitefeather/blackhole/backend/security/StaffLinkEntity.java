package net.onelitefeather.blackhole.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The one-to-one pair of a staff member ({@code oid}) and their Minecraft account. Staff personal
 * data, kept as long as the person is staff. Deliberately not {@code @Serdeable}: entities never
 * leave the backend.
 */
@Entity
@Table(name = "staff_link")
public class StaffLinkEntity {

    @Id
    private UUID oid;

    @Column(name = "minecraft_uuid")
    private UUID minecraftUuid;

    @Column(name = "linked_at")
    private long linkedAt;

    @Column(name = "linked_by_oid")
    private UUID linkedByOid;

    public StaffLinkEntity() {
        // Empty constructor for JPA
    }

    public StaffLinkEntity(UUID oid, UUID minecraftUuid, long linkedAt, UUID linkedByOid) {
        this.oid = oid;
        this.minecraftUuid = minecraftUuid;
        this.linkedAt = linkedAt;
        this.linkedByOid = linkedByOid;
    }

    public UUID getOid() {
        return oid;
    }

    public UUID getMinecraftUuid() {
        return minecraftUuid;
    }

    public long getLinkedAt() {
        return linkedAt;
    }

    public UUID getLinkedByOid() {
        return linkedByOid;
    }
}
