package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.elo.ThresholdEscalationPolicy;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateCache;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentApplicationService;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentTemplateService;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * The punishment services wired over in-memory repositories on top of an {@link EloTestBed}: one
 * fixture per test, a fixed clock, no database. The ELO engine and the punishment service are
 * connected the way they are in the application (escalation applies permanent punishments through
 * the punishment service).
 */
public final class PunishmentTestBed {

    public static final String OWNER = "ab".repeat(64);
    public static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    /** The verified caller of the punishment tests: a signed-in staff member whose oid is {@link #STAFF}. */
    public static final Actor ACTOR = new Actor(STAFF, "Staff", ActorKind.HUMAN, false);
    /** The proxy, which may vouch for an in-game staff member through onBehalfOf. */
    public static final Actor PROXY = new Actor(UUID.nameUUIDFromBytes("proxy".getBytes()), "Proxy", ActorKind.SERVICE, true);

    public final EloTestBed eloBed = new EloTestBed();
    public final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals", args -> this.punishments.all().stream()
                            .filter(p -> args[0].equals(p.getOwner()) && args[1].equals(p.getEscalationGroup()))
                            .filter(p -> !p.isLifted() && p.getCreatedAt() >= (Long) args[2])
                            .count())
                    .finder("findByShortId", args -> this.punishments.all().stream()
                            .filter(p -> args[0].equals(p.getShortId()))
                            .toList())
                    .finder("existsByTemplate_Identifier", args -> this.punishments.all().stream()
                            .anyMatch(p -> p.getTemplate() != null && args[0].equals(p.getTemplate().getIdentifier())))
                    .build();
    public final InMemoryRepository<PunishmentProfileRepository, PunishmentProfileEntity, String> profiles =
            InMemoryRepository.<PunishmentProfileRepository, PunishmentProfileEntity, String>of(
                    PunishmentProfileRepository.class, PunishmentProfileEntity::getOwner).build();
    public final InMemoryRepository<PunishmentTemplateRepository, PunishmentTemplateEntity, UUID> templates =
            InMemoryRepository.<PunishmentTemplateRepository, PunishmentTemplateEntity, UUID>of(
                            PunishmentTemplateRepository.class, PunishmentTemplateEntity::getIdentifier)
                    .assigningIds(t -> new PunishmentTemplateEntity(UUID.randomUUID(), t.getTemplateKey(), t.getReason(), t.getSectionKey(),
                            t.getEscalationGroup(), t.getEscalationWindow(), t.getSteps(), t.getMetaData()))
                    .finder("findByTemplateKey", args -> this.templates.all().stream()
                            .filter(t -> args[0].equals(t.getTemplateKey())).findFirst())
                    .build();
    public final InMemoryProfileSlots slots = new InMemoryProfileSlots(profiles.repository(), punishments.repository());
    public final ProfileCache profileCache = new ProfileCache(eloBed.sharedCache, Duration.ofSeconds(30));
    public final PunishmentTemplateCache templateCache = new PunishmentTemplateCache(eloBed.sharedCache, Duration.ofMinutes(5));
    public final RecordingEvidenceGateway evidence = new RecordingEvidenceGateway();
    public final CaseTestBed cases = new CaseTestBed(eloBed.clock);
    public final StaffLinkTestBed staffLinks = new StaffLinkTestBed(eloBed.events, eloBed.clock);
    public final BanIdTestBed banIds = new BanIdTestBed(punishments.repository());
    public final PunishmentApplicationService punishmentService = new PunishmentApplicationService(
            punishments.repository(), profiles.repository(), slots, templates.repository(), eloBed.sections, eloBed.events,
            profileCache, evidence, cases.timeline, () -> this.elo, this.staffLinks.service, eloBed.clock, banIds.service, Duration.ofDays(365));
    public final PunishmentTemplateService templateService = new PunishmentTemplateService(
            templates.repository(), punishments.repository(), templateCache, eloBed.sections);

    /** The ELO engine, punishing through {@link #punishmentService} exactly as in the application. */
    public final EloService elo = new EloService(
            eloBed.scoreRepository.repository(), new InMemoryEloScoreWriter(eloBed.scoreRepository.repository()),
            eloBed.eventRepository.repository(), eloBed.sections, punishmentService,
            new ThresholdEscalationPolicy(), eloBed.events, eloBed.profileCache, eloBed.clock, 86_400_000L);

    /** Stores a template with the given steps (duration, cost) and returns it. */
    public PunishmentTemplateEntity givenTemplate(String key, String section, String group, TemplateStep... steps) {
        PunishmentTemplateEntity template = new PunishmentTemplateEntity(
                UUID.randomUUID(), key, "reason of " + key, section, group == null ? key : group, null, List.of(steps), new HashMap<>());
        templates.given(template, template.getIdentifier());
        return template;
    }

    public static TemplateStep step(String duration, int cost) {
        return new TemplateStep(duration, cost);
    }

    /** Adds an enabled section with default numbers to the shared section registry. */
    public void givenSection(String key, EnforcementKind enforcement) {
        eloBed.addSection(key, enforcement, "P3D");
    }
}
