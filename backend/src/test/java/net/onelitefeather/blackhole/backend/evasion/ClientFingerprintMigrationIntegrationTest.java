package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The client fingerprint table: one row per fingerprint and owner, and no column that could hold a trait. */
@Tag("integration")
class ClientFingerprintMigrationIntegrationTest extends PostgresIntegrationTest {

    private Statement statement;

    @BeforeEach
    void freshSchema() throws SQLException {
        String schema = "client_fingerprint_" + Long.toHexString(System.nanoTime());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        this.statement = connection.createStatement();
        this.statement.execute("SET search_path TO " + schema);
    }

    private void row(String fingerprint, String owner) throws SQLException {
        this.statement.execute("INSERT INTO client_fingerprint (fingerprint, scheme, owner_hash, first_seen, last_seen) VALUES ('"
                + fingerprint + "', 'v1', '" + owner + "', 1, 1)");
    }

    @Test
    void aFingerprintAndOwnerPairIsUnique() throws SQLException {
        row("f1", "o1");
        row("f1", "o2");
        row("f2", "o1");

        assertThrows(SQLException.class, () -> row("f1", "o1"));
    }

    @Test
    void theTableHoldsTheHashTheSchemeTheOwnerAndTimesOnly() throws SQLException {
        try (ResultSet columns = this.statement.executeQuery("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'client_fingerprint' AND table_schema = current_schema() ORDER BY column_name")) {
            List<String> names = new ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString(1));
            }
            assertEquals(List.of("fingerprint", "first_seen", "identifier", "last_seen", "occurrence_count", "owner_hash", "scheme"), names);
        }
    }
}
