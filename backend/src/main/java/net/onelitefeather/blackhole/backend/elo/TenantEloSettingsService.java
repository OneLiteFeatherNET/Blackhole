package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.TenantEloSettingsEntity;
import net.onelitefeather.blackhole.backend.database.repository.TenantEloSettingsRepository;

import java.util.UUID;

/**
 * Single source of truth for a tenant's effective ELO configuration - merges the tenant's
 * {@link TenantEloSettingsEntity} row (if any, with possibly-null fields) with the platform-wide
 * defaults. {@link EloService} and the report-reward path both go through this rather than each
 * keeping their own copy of the default values.
 */
@Singleton
public class TenantEloSettingsService {

    private final TenantEloSettingsRepository repository;
    private final int defaultBaseElo;
    private final int defaultPermaBanThreshold;
    private final int defaultReportRewardDelta;

    public TenantEloSettingsService(
            TenantEloSettingsRepository repository,
            @Value("${blackhole.elo.baseline:1000}") int defaultBaseElo,
            @Value("${blackhole.elo.perma-ban-threshold:300}") int defaultPermaBanThreshold,
            @Value("${blackhole.elo.report.reward-delta:50}") int defaultReportRewardDelta
    ) {
        this.repository = repository;
        this.defaultBaseElo = defaultBaseElo;
        this.defaultPermaBanThreshold = defaultPermaBanThreshold;
        this.defaultReportRewardDelta = defaultReportRewardDelta;
    }

    /**
     * Resolves the effective ELO settings for a tenant, filling in the platform defaults for
     * any field the tenant hasn't overridden.
     */
    public EffectiveEloSettings resolve(UUID tenantId) {
        TenantEloSettingsEntity settings = this.repository.findById(tenantId).orElse(null);
        if (settings == null) {
            return new EffectiveEloSettings(
                    this.defaultBaseElo, this.defaultBaseElo,
                    this.defaultPermaBanThreshold, this.defaultPermaBanThreshold,
                    null, null,
                    this.defaultReportRewardDelta
            );
        }
        return new EffectiveEloSettings(
                orDefault(settings.getBaseEloChat(), this.defaultBaseElo),
                orDefault(settings.getBaseEloGameplay(), this.defaultBaseElo),
                orDefault(settings.getPermaBanThresholdChat(), this.defaultPermaBanThreshold),
                orDefault(settings.getPermaBanThresholdGameplay(), this.defaultPermaBanThreshold),
                settings.getPermaBanTemplateChatId(),
                settings.getPermaBanTemplateGameplayId(),
                orDefault(settings.getReportRewardDelta(), this.defaultReportRewardDelta)
        );
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
