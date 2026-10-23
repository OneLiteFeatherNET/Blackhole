package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The active punishment query against a real PostgreSQL schema, migrated by the real migrations. */
@Tag("integration")
class ActivePunishmentJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final AtomicInteger SCHEMAS = new AtomicInteger();
    private static final long NOW = 1_000_000L;
    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private PGSimpleDataSource dataSource;
    private JdbcActivePunishmentReader reader;

    @BeforeEach
    void givenAMigratedSchema() {
        String schema = "active_test_" + SCHEMAS.incrementAndGet();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).locations("classpath:db/migration").load().migrate();
        this.dataSource = new PGSimpleDataSource();
        this.dataSource.setUrl(POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema);
        this.dataSource.setUser(POSTGRES.getUsername());
        this.dataSource.setPassword(POSTGRES.getPassword());
        this.reader = new JdbcActivePunishmentReader(this.dataSource);
        run("INSERT INTO staff_actor (oid, kind, display_name, first_seen_at, last_seen_at) VALUES (?, 'HUMAN', 'Alice', 1, 1)", ALICE);
        run("INSERT INTO punishment_sections (section_key, display_name, enforcement, target_kind, base_score, permanent_threshold, pardon_score, "
                + "permanent_reason, decay_per_day, enabled) VALUES ('cn', 'CloudNet', 'SERVER', 'CLOUDNET_TASK', 1000, 300, 700, 'x', 10, true)");
    }

    private static String owner(int n) {
        return String.format("%02x", n).repeat(64);
    }

    /** Puts a punishment into the owner's active slot. */
    private void givenActive(String id, int owner, String section, boolean permanent, long createdAt, Long expiresAt, boolean automatic,
                             UUID actor, boolean lifted) {
        String meta = "{\"reason\":\"because\"" + (expiresAt == null ? "" : ",\"expiration_date\":" + expiresAt)
                + (automatic ? ",\"automatic\":true" : "") + "}";
        run("INSERT INTO punishment_profiles (owner, meta_data) VALUES (?, '{}'::jsonb) ON CONFLICT DO NOTHING", owner(owner));
        run("INSERT INTO punishments (identifier, source, recorded_by_oid, actor_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ABCDEF01', ?::jsonb)",
                id, UUID.randomUUID(), actor, null, owner(owner), section, permanent, lifted, createdAt, meta);
        run("INSERT INTO active_punishments (owner, section_key, punishment_identifier) VALUES (?, ?, ?)", owner(owner), section, id);
    }

    private void run(String sql, Object... parameters) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + sql, e);
        }
    }

    private static ActivePunishmentReader.Query all() {
        return query(null, null, null, null, null, null, null, ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25);
    }

    private static ActivePunishmentReader.Query query(EnforcementKind kind, String section, ServerTargetKind target, Boolean permanent, Long before,
                                                      Boolean automatic, UUID actor, ActivePunishmentSort sort, SortDirection direction, int page, int size) {
        return new ActivePunishmentReader.Query(kind, section, target, permanent, before, automatic, actor, sort, direction, page, size);
    }

    private static List<String> ids(ActivePunishmentReader.Page page) {
        return page.rows().stream().map(ActivePunishmentReader.Row::punishmentIdentifier).toList();
    }

    private void givenMixedPunishments() {
        givenActive("ban-perm", 1, "network", true, 100, null, true, null, false);
        givenActive("ban-temp", 2, "network", false, 200, NOW + 5_000, false, ALICE, false);
        givenActive("mute-temp", 3, "chat", false, 300, NOW + 90_000, false, BOB, false);
        givenActive("srv-temp", 4, "cn", false, 400, NOW + 1_000, true, null, false);
    }

    @Test
    void expiredAndLiftedPunishmentsAreNotListed() {
        givenMixedPunishments();
        givenActive("ban-expired", 5, "network", false, 50, NOW - 1, false, ALICE, false);
        givenActive("ban-lifted", 6, "network", false, 60, NOW + 10, false, ALICE, true);

        ActivePunishmentReader.Page page = this.reader.list(all(), NOW);

        assertEquals(4, page.total());
        assertFalse(ids(page).contains("ban-expired"), "an ended punishment is not active");
        assertFalse(ids(page).contains("ban-lifted"), "a lifted punishment is not active");
    }

    @Test
    void theKindFilterSeparatesBansMutesAndServerBans() {
        givenMixedPunishments();

        assertEquals(List.of("ban-temp", "ban-perm"), ids(this.reader.list(query(EnforcementKind.BAN, null, null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
        assertEquals(List.of("mute-temp"), ids(this.reader.list(query(EnforcementKind.MUTE, null, null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
        assertEquals(List.of("srv-temp"), ids(this.reader.list(query(EnforcementKind.SERVER, null, null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
    }

    @Test
    void theSectionAndCloudNetTargetFiltersNarrowTheList() {
        givenMixedPunishments();

        assertEquals(List.of("mute-temp"), ids(this.reader.list(query(null, "chat", null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
        assertEquals(List.of("srv-temp"), ids(this.reader.list(query(null, null, ServerTargetKind.CLOUDNET_TASK, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
    }

    @Test
    void permanentAndTemporaryAreFilteredApart() {
        givenMixedPunishments();

        assertEquals(List.of("ban-perm"), ids(this.reader.list(query(null, null, null, true, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
        assertEquals(3, this.reader.list(query(null, null, null, false, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW).total());
    }

    @Test
    void expiringBeforeKeepsOnlyTemporaryPunishmentsEndingEarlier() {
        givenMixedPunishments();

        assertEquals(List.of("srv-temp", "ban-temp"), ids(this.reader.list(query(null, null, null, null, NOW + 10_000L, null, null,
                ActivePunishmentSort.EXPIRES, SortDirection.ASC, 0, 25), NOW)));
    }

    @Test
    void automaticAndManualAreFilteredApart() {
        givenMixedPunishments();

        assertEquals(List.of("srv-temp", "ban-perm"), ids(this.reader.list(query(null, null, null, null, null, true, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
        assertEquals(List.of("mute-temp", "ban-temp"), ids(this.reader.list(query(null, null, null, null, null, false, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW)));
    }

    @Test
    void theActorFilterFindsThePunishmentsOfOneStaffMember() {
        givenMixedPunishments();

        ActivePunishmentReader.Page page = this.reader.list(query(null, null, null, null, null, null, ALICE,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW);

        assertEquals(List.of("ban-temp"), ids(page));
        assertEquals("Alice", page.rows().getFirst().actorName());
    }

    @Test
    void aPunishmentWithoutAVerifiedActorShowsNoName() {
        givenMixedPunishments();

        ActivePunishmentReader.Row automatic = this.reader.list(query(null, "network", null, true, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW).rows().getFirst();
        ActivePunishmentReader.Row unknownStaff = this.reader.list(query(null, "chat", null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW).rows().getFirst();

        assertNull(automatic.actorName());
        assertNull(unknownStaff.actorName(), "an actor without a staff_actor row has no display name");
    }

    @Test
    void expirySortingPlacesPermanentPunishmentsAsTheLatest() {
        givenMixedPunishments();

        assertEquals(List.of("srv-temp", "ban-temp", "mute-temp", "ban-perm"), ids(this.reader.list(query(null, null, null, null, null, null, null,
                ActivePunishmentSort.EXPIRES, SortDirection.ASC, 0, 25), NOW)));
        assertEquals("ban-perm", ids(this.reader.list(query(null, null, null, null, null, null, null,
                ActivePunishmentSort.EXPIRES, SortDirection.DESC, 0, 25), NOW)).getFirst());
    }

    @Test
    void pagesSplitTheListAndKeepTheTotal() {
        givenMixedPunishments();

        ActivePunishmentReader.Page first = this.reader.list(query(null, null, null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.ASC, 0, 3), NOW);
        ActivePunishmentReader.Page second = this.reader.list(query(null, null, null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.ASC, 1, 3), NOW);

        assertEquals(List.of("ban-perm", "ban-temp", "mute-temp"), ids(first));
        assertEquals(List.of("srv-temp"), ids(second));
        assertEquals(4, first.total());
        assertEquals(4, second.total());
    }

    @Test
    void aRowCarriesSectionReasonAndNeverARawIdentifier() {
        givenMixedPunishments();

        ActivePunishmentReader.Row row = this.reader.list(query(null, "cn", null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25), NOW).rows().getFirst();

        assertEquals("CloudNet", row.sectionName());
        assertEquals(EnforcementKind.SERVER, row.kind());
        assertEquals("because", row.reason());
        assertEquals("#ABCDEF01", row.shortId());
        assertEquals(owner(4), row.owner());
        assertTrue(row.automatic());
        assertNull(row.caseStatus(), "no case row yet");
    }
}
