package net.onelitefeather.blackhole.backend.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import net.onelitefeather.blackhole.backend.security.StaffLinkCodeEntity;
import net.onelitefeather.blackhole.backend.security.StaffLinkCodeGenerator;
import net.onelitefeather.blackhole.backend.security.StaffLinkCodeRepository;
import net.onelitefeather.blackhole.backend.security.StaffLinkEntity;
import net.onelitefeather.blackhole.backend.security.StaffLinkProperties;
import net.onelitefeather.blackhole.backend.security.StaffLinkRepository;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import net.onelitefeather.blackhole.backend.staffprofile.StaffAvatarJob;
import net.onelitefeather.blackhole.backend.staffprofile.StaffAvatarKeys;
import net.onelitefeather.blackhole.backend.staffprofile.StaffProfileService;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link StaffLinkService} over in-memory repositories, for tests of services that attribute
 * in-game actions. Each test bed builds its own, so tests share nothing.
 */
public final class StaffLinkTestBed {

    /** The code lifetime the bed configures. */
    public static final Duration TTL = Duration.ofMinutes(5);

    public final InMemoryRepository<StaffLinkRepository, StaffLinkEntity, UUID> links =
            InMemoryRepository.<StaffLinkRepository, StaffLinkEntity, UUID>of(StaffLinkRepository.class, StaffLinkEntity::getOid)
                    .finder("findByMinecraftUuid", args -> this.links.all().stream()
                            .filter(link -> link.getMinecraftUuid().equals(args[0])).findFirst())
                    .build();
    public final InMemoryRepository<StaffLinkCodeRepository, StaffLinkCodeEntity, UUID> codes =
            InMemoryRepository.<StaffLinkCodeRepository, StaffLinkCodeEntity, UUID>of(StaffLinkCodeRepository.class, StaffLinkCodeEntity::getOid)
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
    public final InMemoryStaffProfiles profiles = new InMemoryStaffProfiles();
    public final InMemoryStaffAvatars avatars = new InMemoryStaffAvatars();
    /** The avatar job over in-memory stores and a source that never has a skin: no network in tests. */
    public final StaffAvatarJob avatarJob;
    public final StaffProfileService profileService;
    public final StaffLinkService service;
    public final InMemoryRepository<net.onelitefeather.blackhole.backend.exemption.PlayerExemptionRepository,
            net.onelitefeather.blackhole.backend.exemption.PlayerExemptionEntity,
            net.onelitefeather.blackhole.backend.exemption.PlayerExemptionId> exemptionRows =
            InMemoryRepository.<net.onelitefeather.blackhole.backend.exemption.PlayerExemptionRepository,
                    net.onelitefeather.blackhole.backend.exemption.PlayerExemptionEntity,
                    net.onelitefeather.blackhole.backend.exemption.PlayerExemptionId>of(
                    net.onelitefeather.blackhole.backend.exemption.PlayerExemptionRepository.class,
                    net.onelitefeather.blackhole.backend.exemption.PlayerExemptionEntity::getId)
                    .finder("findByIdOwner", args -> this.exemptionRows.all().stream()
                            .filter(row -> row.getId().getOwner().equals(args[0])).toList())
                    .build();
    public final net.onelitefeather.blackhole.backend.exemption.ExemptionService exemptions;
    public final net.onelitefeather.blackhole.backend.exemption.ExemptionGuard guard;

    public StaffLinkTestBed(DomainEventPublisher events, Clock clock) {
        this(events, clock, new StaffAuditRecorder(new InMemoryStaffAuditStore(),
                new DomainEventPublisher(new RecordingEventTransport(), clock), new AuditPayloadGuard(), clock, new SimpleMeterRegistry()));
    }

    /** The audit recorder is the caller's, so a test can read the entries its unlink leaves. */
    public StaffLinkTestBed(DomainEventPublisher events, Clock clock, StaffAuditRecorder audit) {
        this.avatarJob = new StaffAvatarJob(this.profiles, this.avatars,
                new StaffAvatarKeys("test-staff-avatar-secret"), uuid -> Optional.empty(), clock);
        this.profileService = new StaffProfileService(this.profiles, this.avatarJob, clock);
        this.exemptions = new net.onelitefeather.blackhole.backend.exemption.ExemptionService(
                this.exemptionRows.repository(), this.actors.repository(), clock, true);
        this.service = new StaffLinkService(this.links.repository(), this.codes.repository(), this.actors.repository(),
                new StaffLinkCodeGenerator(), events, new StaffLinkProperties(TTL), clock, this.avatarJob, audit, this.exemptions);
        this.guard = new net.onelitefeather.blackhole.backend.exemption.ExemptionGuard(this.exemptions, this.service, events);
    }

    /** Stores a link as if it was confirmed earlier. */
    public StaffLinkEntity givenLink(UUID oid, UUID minecraftUuid) {
        StaffLinkEntity link = new StaffLinkEntity(oid, minecraftUuid, 1L, oid);
        this.links.given(link, oid);
        return link;
    }

    /** Stores the sign-in record of a staff member, with the given display name. */
    public void givenActor(UUID oid, String displayName) {
        this.actors.given(new StaffActorEntity(oid, net.onelitefeather.blackhole.backend.security.ActorKind.HUMAN, displayName, 1L), oid);
    }

    /** The current link of a staff member. */
    public Optional<StaffLinkEntity> linkOf(UUID oid) {
        return this.links.repository().findById(oid);
    }
}
