package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.STAFF_REQUESTER;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.SERVICE;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.STAFF;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.count;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.givenKick;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.givenPunishment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The preview: counts, the frozen set, its limits and its read-only promise, against a migrated schema. */
@Tag("integration")
class RollbackPreviewIntegrationTest extends PostgresIntegrationTest {

    private static final long NOW = 1_000_000L;
    private static final Duration TTL = Duration.ofHours(1);
    private static final long FROM = 0L;
    private static final long TO = 2_000_000L;
    private static final long INSIDE = 500_000L;

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void givenAMigratedSchema() {
        this.dataSource = RollbackTestSchema.fresh(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private RollbackPreviewService service(int maxItems) {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        return new RollbackPreviewService(new JdbcRollbackSelectionReader(this.dataSource), new JdbcRollbackJobStore(this.dataSource),
                clock, TTL, maxItems);
    }

    private RollbackDTO preview(RollbackPreviewService service, UUID staff) {
        return service.preview(new RollbackDTO.PreviewRequest(staff, null, FROM, TO), STAFF_REQUESTER);
    }

    @Test
    void thePreviewCountsRevocablePunishmentsPerKindAndSection() {
        givenPunishment(this.dataSource, "ban-1", 1, "network", STAFF, STAFF, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "ban-2", 2, "network", STAFF, STAFF, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "mute-1", 3, "chat", STAFF, STAFF, null, INSIDE, false, true);

        RollbackDTO.Response response = assertInstanceOf(RollbackDTO.Response.class, preview(service(10), STAFF));

        assertEquals(3, response.revocable(), "every active punishment of the actor is revocable");
        assertEquals(2, (int) response.bySection().get("network"));
        assertEquals(1, (int) response.bySection().get("chat"));
        assertEquals(3, (int) response.byKind().get("PUNISHMENT"));
    }

    @Test
    void aLiftedPunishmentIsReportedAsAlreadyInactiveAndNotAsRevocable() {
        givenPunishment(this.dataSource, "ban-active", 1, "network", STAFF, STAFF, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "ban-lifted", 2, "network", STAFF, STAFF, null, INSIDE, true, false);

        RollbackDTO.Response response = assertInstanceOf(RollbackDTO.Response.class, preview(service(10), STAFF));

        assertEquals(1, response.revocable(), "only the slot-holding punishment is revocable");
        assertEquals(1, response.alreadyInactive(), "the lifted one is reported, not dropped");
        assertEquals(1, (int) response.bySection().get("network"));
    }

    @Test
    void aPreviewOverTheMaximumIsRefusedAndWritesNoJob() {
        givenPunishment(this.dataSource, "ban-1", 1, "network", STAFF, STAFF, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "ban-2", 2, "network", STAFF, STAFF, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "ban-3", 3, "network", STAFF, STAFF, null, INSIDE, false, true);

        RollbackDTO result = preview(service(2), STAFF);

        assertEquals(ErrorKind.ROLLBACK_TOO_LARGE, assertInstanceOf(RollbackDTO.Error.class, result).kind());
        assertEquals(0, count(this.dataSource, "SELECT count(*) FROM staff_rollback_job"), "a refused preview freezes nothing");
    }

    @Test
    void thePreviewChangesNoPunishmentAndNoSlot() {
        givenPunishment(this.dataSource, "ban-1", 1, "network", STAFF, STAFF, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "ban-2", 2, "network", STAFF, STAFF, null, INSIDE, true, false);
        long slotsBefore = count(this.dataSource, "SELECT count(*) FROM active_punishments");
        long liftedBefore = count(this.dataSource, "SELECT count(*) FROM punishments WHERE lifted");

        preview(service(10), STAFF);

        assertEquals(slotsBefore, count(this.dataSource, "SELECT count(*) FROM active_punishments"), "no slot changed");
        assertEquals(liftedBefore, count(this.dataSource, "SELECT count(*) FROM punishments WHERE lifted"), "no punishment was lifted");
        assertEquals(1, count(this.dataSource, "SELECT count(*) FROM staff_rollback_job WHERE state = 'PREVIEW'"),
                "the only write is the preview job itself");
    }

    @Test
    void theFrozenItemsAreTheRevocablePunishmentsAndNoOthers() {
        givenPunishment(this.dataSource, "ban-active", 1, "network", STAFF, STAFF, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "ban-lifted", 2, "network", STAFF, STAFF, null, INSIDE, true, false);

        preview(service(10), STAFF);

        assertEquals(1, count(this.dataSource, "SELECT count(*) FROM staff_rollback_item WHERE punishment_identifier = 'ban-active'"));
        assertEquals(0, count(this.dataSource, "SELECT count(*) FROM staff_rollback_item WHERE punishment_identifier = 'ban-lifted'"));
    }

    @Test
    void thePreviewExpiresAfterTheConfiguredTimeToLive() {
        givenPunishment(this.dataSource, "ban-1", 1, "network", STAFF, STAFF, null, INSIDE, false, true);

        RollbackDTO.Response response = assertInstanceOf(RollbackDTO.Response.class, preview(service(10), STAFF));

        assertEquals(NOW + TTL.toMillis(), response.previewExpiresAt(), "the expiry comes from the injected clock plus the ttl");
    }

    @Test
    void theKicksInTheWindowAreCountedAndFrozenWithoutBeingRevocable() {
        givenKick(this.dataSource, "kick-1", 4, "network", STAFF, STAFF, INSIDE);

        RollbackDTO.Response response = assertInstanceOf(RollbackDTO.Response.class, preview(service(10), STAFF));

        assertEquals(1, response.kicks(), "the kick is counted for the mark option");
        assertEquals(0, response.revocable(), "a kick is never revocable");
        assertEquals(0, response.alreadyInactive(), "a kick is not a punishment that was lifted");
        assertEquals(1, count(this.dataSource, "SELECT count(*) FROM staff_rollback_item WHERE punishment_identifier = 'kick-1' AND kind = 'KICK'"),
                "the kick is frozen with the set, so a later mark acts on the confirmed set");
    }

    @Test
    void aServiceAccountIsRefusedAsSelectorAndNothingIsWritten() {
        givenPunishment(this.dataSource, "proxy-ban", 1, "network", SERVICE, SERVICE, null, INSIDE, false, true);

        RollbackDTO result = preview(service(10), SERVICE);

        assertEquals(ErrorKind.ROLLBACK_SELECTOR_INVALID, assertInstanceOf(RollbackDTO.Error.class, result).kind(),
                "a rollback may never mean everything the proxy did");
        assertEquals(0, count(this.dataSource, "SELECT count(*) FROM staff_rollback_job"));
    }

    @Test
    void anOidThatIsNoStaffActorIsRefusedAsSelector() {
        RollbackDTO result = preview(service(10), UUID.nameUUIDFromBytes("nobody".getBytes()));

        assertEquals(ErrorKind.ROLLBACK_SELECTOR_INVALID, assertInstanceOf(RollbackDTO.Error.class, result).kind(),
                "a selector that is not a HUMAN staff actor is refused");
    }
}
