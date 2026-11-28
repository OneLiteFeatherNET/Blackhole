package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query plan of the changed-by-others section on a seeded table: the window lookup on {@code case_event} must be answered
 * by {@code idx_case_event_occurred} rather than a scan. The database is migrated by the real Flyway files, as in the analytics tests.
 */
@Tag("integration")
class InboxQueryPlanIntegrationTest extends PostgresIntegrationTest {

    private static final int PUNISHMENTS = 3000;
    private static final String STAFF = "00000000-0000-0000-0000-00000000005a";

    private String name;
    private String url;

    @BeforeEach
    void givenAFreshMigratedDatabase() throws Exception {
        this.name = "inbox_plan_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = POSTGRES.getJdbcUrl().substring(0, POSTGRES.getJdbcUrl().lastIndexOf('/') + 1);
        this.url = prefix + this.name;
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + this.name);
        }
        Flyway.configure()
                .dataSource(this.url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterEach
    void dropTheDatabase() throws Exception {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + this.name + " WITH (FORCE)");
        }
    }

    /** One case with one event per punishment; event i happens at i seconds, so the newest tail of the range is the window. */
    private void seed(Statement statement) throws Exception {
        statement.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, "
                + "meta_data, kind, template_identifier) "
                + "SELECT 'p' || lpad(g::text, 8, '0'), CAST('" + STAFF + "' AS uuid), repeat('a', 128), 'network', false, false, 0, "
                + "lpad(g::text, 8, '0'), '{}'::jsonb, 'PUNISHMENT', NULL FROM generate_series(1, " + PUNISHMENTS + ") AS g");
        statement.execute("INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at) "
                + "SELECT 'p' || lpad(g::text, 8, '0'), 'OPEN', 'NORMAL', '[]'::jsonb, 0, 0, 0 FROM generate_series(1, " + PUNISHMENTS + ") AS g");
        statement.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, "
                + "actor_trust, actor_oid, visibility) "
                + "SELECT gen_random_uuid(), 'p' || lpad(g::text, 8, '0'), 'case.status_changed', 1, g * 1000, 'LIVE', 'VERIFIED', "
                + "CAST('" + STAFF + "' AS uuid), 'STAFF' FROM generate_series(1, " + PUNISHMENTS + ") AS g");
        statement.execute("ANALYZE punishment_case");
        statement.execute("ANALYZE case_event");
    }

    @Test
    void theChangedByOthersWindowLookupIsAnsweredByTheOccurredIndex() throws Exception {
        long since = (long) PUNISHMENTS * 1000 - 100_000L;
        List<String> plan = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(this.url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            seed(connection.createStatement());
            try (PreparedStatement statement = connection.prepareStatement("EXPLAIN " + InboxQueries.LATEST_CHANGES)) {
                statement.setLong(1, since);
                statement.setLong(2, since);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        plan.add(rows.getString(1));
                    }
                }
            }
        }

        String text = String.join("\n", plan);
        assertTrue(text.contains("idx_case_event_occurred"), "the window lookup must use idx_case_event_occurred, plan was:\n" + text);
    }
}
