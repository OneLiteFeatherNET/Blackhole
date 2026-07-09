package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.elo.service.EloService;
import java.util.UUID;

/**
 * The network's ELO configuration, built once from static config by {@link EloService}.
 */
public record EffectiveEloSettings(
        int baseEloChat,
        int baseEloGameplay,
        int permaBanThresholdChat,
        int permaBanThresholdGameplay,
        UUID permaBanTemplateChatId,
        UUID permaBanTemplateGameplayId
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
