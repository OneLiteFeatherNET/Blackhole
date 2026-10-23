package net.onelitefeather.blackhole.backend.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The one-to-one pair of a subject (a staff {@code oid} or a hashed player owner) and a Discord
 * account. The Discord user id is personal data: it is kept only in this row, which unlinking,
 * an admin or staff anonymisation deletes. Deliberately not {@code @Serdeable}: entities never
 * leave the backend.
 */
@Entity
@Table(name = "discord_link")
public class DiscordLinkEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DiscordLinkKind kind;

    @Column(length = 128)
    private String subject;

    @Column(name = "discord_user_id", length = 24)
    private String discordUserId;

    @Column(name = "linked_at")
    private long linkedAt;

    public DiscordLinkEntity() {
        // Empty constructor for JPA
    }

    public DiscordLinkEntity(UUID id, DiscordLinkKind kind, String subject, String discordUserId, long linkedAt) {
        this.id = id;
        this.kind = kind;
        this.subject = subject;
        this.discordUserId = discordUserId;
        this.linkedAt = linkedAt;
    }

    public UUID getId() {
        return id;
    }

    public DiscordLinkKind getKind() {
        return kind;
    }

    public String getSubject() {
        return subject;
    }

    public String getDiscordUserId() {
        return discordUserId;
    }

    public long getLinkedAt() {
        return linkedAt;
    }
}
