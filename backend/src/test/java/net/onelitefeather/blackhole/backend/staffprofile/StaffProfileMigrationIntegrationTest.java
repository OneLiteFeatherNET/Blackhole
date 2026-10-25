package net.onelitefeather.blackhole.backend.staffprofile;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Runs V26 on an empty schema: the folded display name is unique among named profiles, and unnamed profiles do not
 * collide with each other.
 */
@Tag("integration")
class StaffProfileMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "staff_profile_migration";

    @Test
    void theFoldedDisplayNameIsUniqueAmongNamedProfiles() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA);
            statement.execute("INSERT INTO staff_profile (oid, display_name, display_name_folded, updated_at) "
                    + "VALUES ('11111111-1111-4111-8111-111111111111', 'Mia K.', 'mia k.', 1)");

            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO staff_profile (oid, display_name, display_name_folded, updated_at) "
                    + "VALUES ('22222222-2222-4222-8222-222222222222', 'MIA K.', 'mia k.', 1)"), "the same folded name is refused");
            assertDoesNotThrow(() -> statement.execute("INSERT INTO staff_profile (oid, updated_at) "
                    + "VALUES ('33333333-3333-4333-8333-333333333333', 1)"), "a profile without a name is not a name");
            assertDoesNotThrow(() -> statement.execute("INSERT INTO staff_profile (oid, updated_at) "
                    + "VALUES ('44444444-4444-4444-8444-444444444444', 1)"), "two unnamed profiles do not collide");
        }
    }
}
