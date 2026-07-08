package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.dto.TenantEloSettingsDTO;

import java.util.UUID;

/**
 * A tenant's overrides for the dual-ELO system - at most one row per tenant. Every field is
 * nullable rather than defaulting to a sentinel value, so "not configured yet" can be told apart
 * from "explicitly set to zero"; {@code TenantEloSettingsService} fills in global defaults for
 * whichever fields are {@code null}.
 */
@Serdeable
@Entity
@Table(name = "tenant_elo_settings")
public class TenantEloSettingsEntity {

    @Id
    private UUID tenantId;

    private Integer baseEloChat;

    private Integer baseEloGameplay;

    private Integer permaBanThresholdChat;

    private Integer permaBanThresholdGameplay;

    private UUID permaBanTemplateChatId;

    private UUID permaBanTemplateGameplayId;

    private Integer reportRewardDelta;

    public TenantEloSettingsEntity() {
        // Empty constructor for JPA
    }

    public TenantEloSettingsEntity(
            UUID tenantId,
            Integer baseEloChat,
            Integer baseEloGameplay,
            Integer permaBanThresholdChat,
            Integer permaBanThresholdGameplay,
            UUID permaBanTemplateChatId,
            UUID permaBanTemplateGameplayId,
            Integer reportRewardDelta
    ) {
        this.tenantId = tenantId;
        this.baseEloChat = baseEloChat;
        this.baseEloGameplay = baseEloGameplay;
        this.permaBanThresholdChat = permaBanThresholdChat;
        this.permaBanThresholdGameplay = permaBanThresholdGameplay;
        this.permaBanTemplateChatId = permaBanTemplateChatId;
        this.permaBanTemplateGameplayId = permaBanTemplateGameplayId;
        this.reportRewardDelta = reportRewardDelta;
    }

    public static TenantEloSettingsEntity toEntity(TenantEloSettingsDTO dto) {
        return new TenantEloSettingsEntity(
                dto.tenantId(),
                dto.baseEloChat(),
                dto.baseEloGameplay(),
                dto.permaBanThresholdChat(),
                dto.permaBanThresholdGameplay(),
                dto.permaBanTemplateChatId(),
                dto.permaBanTemplateGameplayId(),
                dto.reportRewardDelta()
        );
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Integer getBaseEloChat() {
        return baseEloChat;
    }

    public Integer getBaseEloGameplay() {
        return baseEloGameplay;
    }

    public Integer getPermaBanThresholdChat() {
        return permaBanThresholdChat;
    }

    public Integer getPermaBanThresholdGameplay() {
        return permaBanThresholdGameplay;
    }

    public UUID getPermaBanTemplateChatId() {
        return permaBanTemplateChatId;
    }

    public UUID getPermaBanTemplateGameplayId() {
        return permaBanTemplateGameplayId;
    }

    public Integer getReportRewardDelta() {
        return reportRewardDelta;
    }

    public TenantEloSettingsDTO toDTO() {
        return new TenantEloSettingsDTO(
                this.tenantId,
                this.baseEloChat,
                this.baseEloGameplay,
                this.permaBanThresholdChat,
                this.permaBanThresholdGameplay,
                this.permaBanTemplateChatId,
                this.permaBanTemplateGameplayId,
                this.reportRewardDelta
        );
    }
}
