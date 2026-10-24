package net.onelitefeather.blackhole.backend.discord;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DiscordLinkRepository extends CrudRepository<DiscordLinkEntity, UUID> {

    Optional<DiscordLinkEntity> findByKindAndSubject(DiscordLinkKind kind, String subject);

    Optional<DiscordLinkEntity> findByKindAndDiscordUserId(DiscordLinkKind kind, String discordUserId);

    List<DiscordLinkEntity> findByDiscordUserId(String discordUserId);

    List<DiscordLinkEntity> findByKind(DiscordLinkKind kind);
}
