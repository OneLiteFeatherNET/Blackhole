package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.dto.EloTrack;

import java.util.UUID;

/**
 * A tenant's fully-resolved ELO configuration - every {@code TenantEloSettingsEntity} override
 * merged with the platform-wide defaults for whichever fields the tenant hasn't customized. This
 * is what {@link EloService} and report-reward logic actually compute against; the raw,
 * partially-{@code null} per-tenant row is only ever exposed as-is via the settings API.
 */
public record EffectiveEloSettings(
        int baseEloChat,
        int baseEloGameplay,
        int permaBanThresholdChat,
        int permaBanThresholdGameplay,
        UUID permaBanTemplateChatId,
        UUID permaBanTemplateGameplayId,
        int reportRewardDelta
) {

    public int baseElo(EloTrack track) {
        return track == EloTrack.CHAT ? this.baseEloChat : this.baseEloGameplay;
    }

    public int permaBanThreshold(EloTrack track) {
        return track == EloTrack.CHAT ? this.permaBanThresholdChat : this.permaBanThresholdGameplay;
    }

    public UUID permaBanTemplateId(EloTrack track) {
        return track == EloTrack.CHAT ? this.permaBanTemplateChatId : this.permaBanTemplateGameplayId;
    }
}
