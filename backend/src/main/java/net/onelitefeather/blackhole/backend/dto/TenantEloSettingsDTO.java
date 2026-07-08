package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * A tenant's raw ELO setting overrides. Every field except {@code tenantId} may be {@code null},
 * meaning "inherit the platform default" - see {@code TenantEloSettingsService} for the merge.
 */
@Serdeable
@ReflectiveAccess
public record TenantEloSettingsDTO(
        @NonNull UUID tenantId,
        @Nullable Integer baseEloChat,
        @Nullable Integer baseEloGameplay,
        @Nullable Integer permaBanThresholdChat,
        @Nullable Integer permaBanThresholdGameplay,
        @Nullable UUID permaBanTemplateChatId,
        @Nullable UUID permaBanTemplateGameplayId,
        @Nullable Integer reportRewardDelta
) {
}
