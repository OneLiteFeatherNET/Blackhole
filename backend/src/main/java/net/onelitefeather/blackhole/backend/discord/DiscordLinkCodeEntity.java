package net.onelitefeather.blackhole.backend.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The live link code of one subject: only a hash of the code, never the code itself, and the moment
 * it stops working. There is at most one row per subject ({@code ownerKey} is {@code KIND:subject}).
 * Deliberately not {@code @Serdeable}: entities never leave the backend.
 */
@Entity
@Table(name = "discord_link_code")
public class DiscordLinkCodeEntity {

    @Id
    @Column(name = "owner_key", length = 160)
    private String ownerKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DiscordLinkKind kind;

    @Column(length = 128)
    private String subject;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "expires_at")
    private long expiresAt;

    public DiscordLinkCodeEntity() {
        // Empty constructor for JPA
    }

    public DiscordLinkCodeEntity(DiscordLinkKind kind, String subject, String codeHash, long expiresAt) {
        this.ownerKey = ownerKey(kind, subject);
        this.kind = kind;
        this.subject = subject;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public static String ownerKey(DiscordLinkKind kind, String subject) {
        return kind.name() + ":" + subject;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public DiscordLinkKind getKind() {
        return kind;
    }

    public String getSubject() {
        return subject;
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
