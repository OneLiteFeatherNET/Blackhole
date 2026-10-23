package net.onelitefeather.blackhole.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The live link code of one staff member: only a hash of the code, never the code itself, and the
 * moment it stops working. There is at most one row per {@code oid}. Deliberately not
 * {@code @Serdeable}: entities never leave the backend.
 */
@Entity
@Table(name = "staff_link_code")
public class StaffLinkCodeEntity {

    @Id
    private UUID oid;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "expires_at")
    private long expiresAt;

    public StaffLinkCodeEntity() {
        // Empty constructor for JPA
    }

    public StaffLinkCodeEntity(UUID oid, String codeHash, long expiresAt) {
        this.oid = oid;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public UUID getOid() {
        return oid;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
