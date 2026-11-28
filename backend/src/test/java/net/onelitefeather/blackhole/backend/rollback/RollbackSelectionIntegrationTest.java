package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.MINECRAFT_UNLINKED;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.OTHER_STAFF;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.PROXY_OID;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.STAFF;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.givenPunishment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which punishments a staff selector matches, against a migrated PostgreSQL schema. */
@Tag("integration")
class RollbackSelectionIntegrationTest extends PostgresIntegrationTest {

    private static final long FROM = 1_000L;
    private static final long TO = 9_000L;
    private static final long INSIDE = 5_000L;
    private static final UUID OTHER_ACCOUNT = UUID.nameUUIDFromBytes("minecraft-other".getBytes());

    private PGSimpleDataSource dataSource;
    private JdbcRollbackSelectionReader reader;

    @BeforeEach
    void givenAMigratedSchema() {
        this.dataSource = RollbackTestSchema.fresh(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        this.reader = new JdbcRollbackSelectionReader(this.dataSource);
    }

    private Set<String> identifiers(RollbackSelector selector) {
        return this.reader.select(selector, FROM, TO).stream().map(RollbackSelectionReader.Candidate::identifier)
                .collect(Collectors.toSet());
    }

    @Test
    void aLinkedInGameAndAWebPunishmentOfTheSameStaffMemberAreBothSelected() {
        givenPunishment(this.dataSource, "ban-linked", 1, "network", MINECRAFT_UNLINKED, PROXY_OID, STAFF, INSIDE, false, true);
        givenPunishment(this.dataSource, "mute-web", 2, "chat", STAFF, STAFF, null, INSIDE, false, true);

        assertEquals(Set.of("ban-linked", "mute-web"), identifiers(RollbackSelector.staffActor(STAFF)));
    }

    @Test
    void anUnlinkedInGameRowOfTheProxyMatchesNoHumanActor() {
        givenPunishment(this.dataSource, "ban-unlinked", 3, "network", MINECRAFT_UNLINKED, PROXY_OID, null, INSIDE, false, true);

        assertFalse(identifiers(RollbackSelector.staffActor(STAFF)).contains("ban-unlinked"),
                "a proxy write by an unlinked account belongs to no human staff actor");
    }

    @Test
    void anUnlinkedAccountIsSelectableOnlyByItsMinecraftUuid() {
        givenPunishment(this.dataSource, "ban-unlinked", 3, "network", MINECRAFT_UNLINKED, PROXY_OID, null, INSIDE, false, true);
        givenPunishment(this.dataSource, "ban-other-account", 4, "network", OTHER_ACCOUNT, PROXY_OID, null, INSIDE, false, true);

        assertEquals(Set.of("ban-unlinked"), identifiers(RollbackSelector.unlinkedInGame(MINECRAFT_UNLINKED)));
    }

    @Test
    void aPunishmentOneSecondBeforeTheWindowIsNotSelected() {
        givenPunishment(this.dataSource, "too-early", 5, "network", STAFF, STAFF, null, FROM - 1, false, true);
        givenPunishment(this.dataSource, "at-start", 6, "network", STAFF, STAFF, null, FROM, false, true);

        assertEquals(Set.of("at-start"), identifiers(RollbackSelector.staffActor(STAFF)));
    }

    @Test
    void anAutomaticPunishmentWithoutAStaffActorIsNeverSelected() {
        givenPunishment(this.dataSource, "elo-mute", 7, "chat", null, null, null, INSIDE, false, true);

        assertFalse(identifiers(RollbackSelector.staffActor(STAFF)).contains("elo-mute"));
        assertFalse(identifiers(RollbackSelector.staffActor(OTHER_STAFF)).contains("elo-mute"));
    }

    @Test
    void aLiftedPunishmentIsSelectedButNotRevocable() {
        givenPunishment(this.dataSource, "lifted-ban", 8, "network", STAFF, STAFF, null, INSIDE, true, false);

        List<RollbackSelectionReader.Candidate> rows = this.reader.select(RollbackSelector.staffActor(STAFF), FROM, TO);

        assertEquals(1, rows.size(), "the lifted row is still part of the selection so the preview can report it");
        assertFalse(rows.getFirst().revocable(), "a row outside its section slot cannot be revoked");
    }

    @Test
    void theSelectionOfAnotherStaffMemberIsEmpty() {
        givenPunishment(this.dataSource, "alice-ban", 9, "network", STAFF, STAFF, null, INSIDE, false, true);

        assertTrue(identifiers(RollbackSelector.staffActor(OTHER_STAFF)).isEmpty(), "the window and the actor both have to match");
    }
}
