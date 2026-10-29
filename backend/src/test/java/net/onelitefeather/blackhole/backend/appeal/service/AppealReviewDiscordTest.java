package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealReviewResult;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import net.onelitefeather.blackhole.backend.security.StaffDelegation;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingProtocols;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Discord bot decides an appeal for a linked staff member: the decision is theirs, the bot is recorded, the self-review rule and the
 * message rules hold, and one audit entry is written for a success only.
 */
class AppealReviewDiscordTest {

    private static final UUID MODERATOR = UUID.nameUUIDFromBytes("discord-moderator".getBytes());
    private static final UUID BOT = UUID.nameUUIDFromBytes("discord-bot".getBytes());
    private static final Actor BOT_ACTOR = new Actor(BOT, "Blackhole Discord", ActorKind.SERVICE, false, false, false, true);

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final List<DiscordLinkEntity> linkRows = new ArrayList<>();
    private final InMemoryRepository<DiscordLinkRepository, DiscordLinkEntity, UUID> links =
            InMemoryRepository.<DiscordLinkRepository, DiscordLinkEntity, UUID>of(DiscordLinkRepository.class, DiscordLinkEntity::getId)
                    .finder("findByKindAndSubject", args -> this.linkRows.stream()
                            .filter(link -> link.getKind() == args[0] && link.getSubject().equals(args[1])).findFirst())
                    .build();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> actors =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final StaffDelegation delegation = new StaffDelegation(this.links.repository(), this.actors.repository(), this.bed.eloBed.clock,
            Duration.ofHours(24));
    private final AppealDecisionService service = new AppealDecisionService(
            this.appeals.repository(), this.bed.punishments.repository(), this.bed.profiles.repository(), this.bed.slots, this.bed.profileCache,
            this.bed.elo, this.bed.eloBed.sections, this.bed.eloBed.events, this.bed.evidence, this.bed.staffLinks.service, this.bed.cases.timeline,
            this.bed.banIds.service, new RecordingProtocols(), this.bed.staffLinks.profileService, this.delegation,
            this.bed.eloBed.clock, this.bed.audit);

    /** Links a staff member to Discord and gives them a MODERATOR snapshot taken just now. */
    private void givenLinkedModerator(UUID staff) {
        this.linkRows.add(new DiscordLinkEntity(UUID.randomUUID(), DiscordLinkKind.STAFF, staff.toString(), "123456789012345678", 1L));
        StaffActorEntity row = new StaffActorEntity(staff, ActorKind.HUMAN, "Moderator", 1L);
        row.setRoles("MODERATOR");
        row.setRolesSeenAt(this.bed.eloBed.clock.millis());
        this.actors.repository().save(row);
    }

    private PunishmentEntity givenStaffMute() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 80));
        PunishmentDTO.ApplyResponse applied = (PunishmentDTO.ApplyResponse) this.bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
        return this.bed.punishments.all().stream().filter(p -> p.getIdentifier().equals(applied.punishment().identifier())).findFirst()
                .orElseThrow();
    }

    private UUID givenAppeal(PunishmentEntity punishment) {
        AppealEntity appeal = new AppealEntity(punishment, OWNER, "please", AppealStatus.ELIGIBLE_PENDING_REVIEW,
                new HashMap<>(Map.of("severityTier", "STANDARD")), 0L, 0L, new HashMap<>());
        UUID id = UUID.randomUUID();
        this.appeals.given(appeal, id);
        return id;
    }

    private AppealReviewResult reviewFromDiscord(UUID appeal, AppealReviewDTO review) {
        return this.service.reviewAppeal(appeal, review, BOT_ACTOR);
    }

    private static AppealReviewDTO accept(UUID staff) {
        return new AppealReviewDTO(AppealStatus.GRANTED_FULL_LIFT, "ok", null, null, staff);
    }

    @Test
    void anAcceptByALinkedModeratorIsDecidedByThatMemberAndRecordedByTheBot() {
        givenLinkedModerator(MODERATOR);
        UUID appeal = givenAppeal(givenStaffMute());

        AppealReviewResult result = reviewFromDiscord(appeal, accept(MODERATOR));

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), String.valueOf(result.message()));
        assertEquals(MODERATOR, result.appeal().getDecidedBy(), "the decision is the staff member's");
        assertEquals(BOT, result.appeal().getRecordedByOid(), "the bot is the recording principal");
    }

    @Test
    void theIssuerOfThePunishmentCannotDecideItFromDiscord() {
        givenLinkedModerator(ACTOR.oid());
        UUID appeal = givenAppeal(givenStaffMute());

        AppealReviewResult result = reviewFromDiscord(appeal, accept(ACTOR.oid()));

        assertEquals(AppealReviewResult.Kind.SELF_REVIEW, result.kind(), "self-review uses the attributed oid");
    }

    @Test
    void aSecondDecisionFromDiscordIsNotAwaitingReview() {
        givenLinkedModerator(MODERATOR);
        UUID appeal = givenAppeal(givenStaffMute());
        assertEquals(AppealReviewResult.Kind.DECIDED, reviewFromDiscord(appeal, accept(MODERATOR)).kind(), "precondition");

        AppealReviewResult second = reviewFromDiscord(appeal, accept(MODERATOR));

        assertEquals(AppealReviewResult.Kind.NOT_AWAITING_REVIEW, second.kind(), "a second decision is a conflict");
    }

    @Test
    void aRejectWithoutAMessageFromDiscordIsRefused() {
        givenLinkedModerator(MODERATOR);
        UUID appeal = givenAppeal(givenStaffMute());

        AppealReviewResult result = reviewFromDiscord(appeal, new AppealReviewDTO(AppealStatus.DENIED, null, null, null, MODERATOR));

        assertEquals(AppealReviewResult.Kind.INVALID_DECISION, result.kind(), "a reject from Discord needs the message to the player");
    }

    @Test
    void aMemberWithoutALiveLinkIsForbiddenAndNothingIsDecided() {
        UUID appeal = givenAppeal(givenStaffMute());

        AppealReviewResult result = reviewFromDiscord(appeal, accept(MODERATOR));

        assertEquals(AppealReviewResult.Kind.DELEGATION_FORBIDDEN, result.kind(), "no live link, no decision");
        assertEquals(AppealStatus.ELIGIBLE_PENDING_REVIEW, this.appeals.repository().findById(appeal).orElseThrow().getStatus(),
                "the appeal stays undecided");
    }

    @Test
    void aDecisionFromDiscordWritesOneDiscordAuditEntry() {
        givenLinkedModerator(MODERATOR);
        UUID appeal = givenAppeal(givenStaffMute());

        reviewFromDiscord(appeal, accept(MODERATOR));

        var reviews = this.bed.auditStore.rows().stream().filter(row -> row.action().equals("appeal.review")).toList();
        assertEquals(1, reviews.size(), "one appeal.review entry for the success");
        assertEquals(net.onelitefeather.blackhole.backend.audit.Surface.DISCORD, reviews.getFirst().surface(), "the Discord surface");
    }
}
