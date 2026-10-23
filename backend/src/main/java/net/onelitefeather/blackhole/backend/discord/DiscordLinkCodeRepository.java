package net.onelitefeather.blackhole.backend.discord;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@Repository
public interface DiscordLinkCodeRepository extends CrudRepository<DiscordLinkCodeEntity, String> {

    Optional<DiscordLinkCodeEntity> findByCodeHash(String codeHash);

    /** Removes every code that expired at or before {@code now}. */
    void deleteByExpiresAtLessThanEquals(long now);
}
