package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.discord.DiscordLinkCodeEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkCodeRepository;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkProperties;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import net.onelitefeather.blackhole.backend.security.StaffLinkCodeGenerator;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link DiscordLinkService} over in-memory repositories. Each test bed builds its own, so tests
 * share nothing.
 */
public final class DiscordLinkTestBed {

    /** The code lifetime the bed configures. */
    public static final Duration TTL = Duration.ofMinutes(10);

    public final InMemoryRepository<DiscordLinkRepository, DiscordLinkEntity, UUID> links =
            InMemoryRepository.<DiscordLinkRepository, DiscordLinkEntity, UUID>of(DiscordLinkRepository.class, DiscordLinkEntity::getId)
                    .finder("findByKindAndSubject", args -> this.links.all().stream()
                            .filter(link -> link.getKind() == args[0] && link.getSubject().equals(args[1])).findFirst())
                    .finder("findByKindAndDiscordUserId", args -> this.links.all().stream()
                            .filter(link -> link.getKind() == args[0] && link.getDiscordUserId().equals(args[1])).findFirst())
                    .finder("findByDiscordUserId", args -> this.links.all().stream()
                            .filter(link -> link.getDiscordUserId().equals(args[0])).toList())
                    .build();
    public final InMemoryRepository<DiscordLinkCodeRepository, DiscordLinkCodeEntity, String> codes =
            InMemoryRepository.<DiscordLinkCodeRepository, DiscordLinkCodeEntity, String>of(DiscordLinkCodeRepository.class, DiscordLinkCodeEntity::getOwnerKey)
                    .finder("findByCodeHash", args -> this.codes.all().stream()
                            .filter(code -> code.getCodeHash().equals(args[0])).findFirst())
                    .finder("deleteByExpiresAtLessThanEquals", args -> {
                        this.codes.all().stream().filter(code -> code.getExpiresAt() <= (long) args[0])
                                .forEach(this.codes.repository()::delete);
                        return null;
                    })
                    .build();
    public final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> actors =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    public final DiscordLinkService service;

    public DiscordLinkTestBed(DomainEventPublisher events, Clock clock) {
        this.service = new DiscordLinkService(this.links.repository(), this.codes.repository(), this.actors.repository(),
                new StaffLinkCodeGenerator(), events, new DiscordLinkProperties(TTL), clock);
    }

    /** Stores a link as if it was confirmed earlier. */
    public DiscordLinkEntity givenLink(DiscordLinkKind kind, String subject, String discordUserId) {
        DiscordLinkEntity link = new DiscordLinkEntity(UUID.randomUUID(), kind, subject, discordUserId, 1L);
        this.links.given(link, link.getId());
        return link;
    }

    /** Stores the sign-in record of a staff member, with the given display name. */
    public void givenActor(UUID oid, String displayName) {
        this.actors.given(new StaffActorEntity(oid, ActorKind.HUMAN, displayName, 1L), oid);
    }

    /** The current link of a subject. */
    public Optional<DiscordLinkEntity> linkOf(DiscordLinkKind kind, String subject) {
        return this.links.all().stream().filter(link -> link.getKind() == kind && link.getSubject().equals(subject)).findFirst();
    }

    /** Every stored link. */
    public List<DiscordLinkEntity> allLinks() {
        return this.links.all();
    }
}
