package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
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

/** A WARN row is never an active punishment, even if it were ever put in a slot. */
@Tag("integration")
class ActiveReadExcludesWarningTest extends PostgresIntegrationTest {

    private static final AtomicInteger SCHEMAS = new AtomicInteger();
    private static final long NOW = 1_000_000L;

    private PGSimpleDataSource dataSource;
    private JdbcActivePunishmentReader reader;

    @BeforeEach
    void givenAMigratedSchema() {
        String schema = "warn_active_test_" + SCHEMAS.incrementAndGet();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).locations("classpath:db/migration").load().migrate();
        this.dataSource = new PGSimpleDataSource();
        this.dataSource.setUrl(POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema);
        this.dataSource.setUser(POSTGRES.getUsername());
        this.dataSource.setPassword(POSTGRES.getPassword());
        this.reader = new JdbcActivePunishmentReader(this.dataSource);
    }

    private static String owner(int n) {
        return String.format("%02x", n).repeat(64);
    }

    private void givenActive(String id, int owner, String kind) {
        run("INSERT INTO punishment_profiles (owner, meta_data) VALUES (?, '{}'::jsonb) ON CONFLICT DO NOTHING", owner(owner));
        run("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, kind, meta_data) "
                + "VALUES (?, ?, ?, 'chat', false, false, 100, 'ABCDEF01', ?, '{}'::jsonb)", id, UUID.randomUUID(), owner(owner), kind);
        run("INSERT INTO active_punishments (owner, section_key, punishment_identifier) VALUES (?, 'chat', ?)", owner(owner), id);
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

    private List<String> listed() {
        ActivePunishmentReader.Query query = new ActivePunishmentReader.Query(null, null, null, null, null, null, null,
                ActivePunishmentSort.CREATED, SortDirection.DESC, 0, 25);
        return this.reader.list(query, NOW).rows().stream().map(ActivePunishmentReader.Row::punishmentIdentifier).toList();
    }

    @Test
    void aWarnRowIsNotReturnedByTheActiveRead() {
        givenActive("warn-1", 1, "WARN");

        assertEquals(List.of(), listed(), "a warning is not an active punishment");
    }

    @Test
    void aPunishmentRowNextToAWarnIsStillReturned() {
        givenActive("warn-1", 1, "WARN");
        givenActive("mute-1", 2, "PUNISHMENT");

        assertEquals(List.of("mute-1"), listed(), "only the punishment is active");
    }
}
