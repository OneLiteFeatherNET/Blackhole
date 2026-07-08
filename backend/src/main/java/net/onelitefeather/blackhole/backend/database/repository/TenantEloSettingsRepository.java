package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.TenantEloSettingsEntity;

import java.util.UUID;

@Repository
public interface TenantEloSettingsRepository extends PageableRepository<TenantEloSettingsEntity, UUID> {
}
