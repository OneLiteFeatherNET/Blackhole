package net.onelitefeather.blackhole.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One verified principal: its {@code oid}, kind, the name claim and when it first and last wrote.
 * Nothing else about a person is stored (no email, UPN or token). Deliberately not
 * {@code @Serdeable}: entities never leave the backend.
 */
@Entity
@Table(name = "staff_actor")
public class StaffActorEntity {

    @Id
    private UUID oid;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ActorKind kind;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "first_seen_at")
    private long firstSeenAt;

    @Column(name = "last_seen_at")
    private long lastSeenAt;

    /** The staff role names of the last verified HUMAN request, comma-separated ({@code ADMIN}, {@code MODERATOR}, {@code VIEWER}). */
    @Column(length = 128)
    private String roles;

    /** When {@link #roles} was taken from a token; {@code null} until the first HUMAN request. */
    @Column(name = "roles_seen_at")
    private Long rolesSeenAt;

    public StaffActorEntity() {
        // Empty constructor for JPA
    }

    public StaffActorEntity(UUID oid, ActorKind kind, String displayName, long seenAt) {
        this.oid = oid;
        this.kind = kind;
        this.displayName = displayName;
        this.firstSeenAt = seenAt;
        this.lastSeenAt = seenAt;
    }

    public UUID getOid() {
        return oid;
    }

    public ActorKind getKind() {
        return kind;
    }

    public void setKind(ActorKind kind) {
        this.kind = kind;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public long getFirstSeenAt() {
        return firstSeenAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public Long getRolesSeenAt() {
        return rolesSeenAt;
    }

    public void setRolesSeenAt(Long rolesSeenAt) {
        this.rolesSeenAt = rolesSeenAt;
    }
}
