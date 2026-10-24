package net.onelitefeather.blackhole.backend.clientrule;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs the client rule migration on an empty schema and checks what the database itself enforces. */
@Tag("integration")
class ClientRuleMigrationIntegrationTest extends PostgresIntegrationTest {

    private Connection connection;
    private Statement statement;

    @BeforeEach
    void freshSchema() throws SQLException {
        String schema = "client_rule_" + Long.toHexString(System.nanoTime());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        this.connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        this.statement = this.connection.createStatement();
        this.statement.execute("SET search_path TO " + schema);
    }

    private void rule(String key, String kind, String extraColumns, String extraValues) throws SQLException {
        this.statement.execute("INSERT INTO client_rule (rule_key, display_name, kind, scope_kind, action, created_at, updated_at" + extraColumns + ") "
                + "VALUES ('" + key + "', '" + key + "', '" + kind + "', 'GLOBAL', 'NOTIFY', 1, 1" + extraValues + ")");
    }

    private long count(String table) throws SQLException {
        try (ResultSet rows = this.statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void aRuleStartsEnabledWithNotifyAndNoHits() throws SQLException {
        rule("meteor", "CHANNEL", "", "");

        try (ResultSet rows = this.statement.executeQuery("SELECT enabled, action, hit_count, negate, acknowledged_spoofable FROM client_rule")) {
            rows.next();
            assertEquals(true, rows.getBoolean(1), "enabled by default");
            assertEquals("NOTIFY", rows.getString(2));
            assertEquals(0, rows.getLong(3));
            assertEquals(false, rows.getBoolean(4));
            assertEquals(false, rows.getBoolean(5));
        }
    }

    @Test
    void theRuleKeyIsUnique() throws SQLException {
        rule("meteor", "CHANNEL", "", "");

        assertThrows(SQLException.class, () -> rule("meteor", "BRAND", "", ""));
    }

    @Test
    void anUnknownKindActionOrScopeIsRefused() {
        assertThrows(SQLException.class, () -> rule("a", "MODPACK", "", ""));
        assertThrows(SQLException.class, () -> this.statement.execute(
                "INSERT INTO client_rule (rule_key, display_name, kind, scope_kind, action, created_at, updated_at) VALUES ('b', 'b', 'BRAND', 'GLOBAL', 'BAN', 1, 1)"));
        assertThrows(SQLException.class, () -> this.statement.execute(
                "INSERT INTO client_rule (rule_key, display_name, kind, scope_kind, action, created_at, updated_at) VALUES ('c', 'c', 'BRAND', 'EVERYWHERE', 'NOTIFY', 1, 1)"));
    }

    @Test
    void aProtocolRuleNeedsAnOrderedRangeAndOtherKindsMustNotHaveOne() throws SQLException {
        rule("range", "PROTOCOL", ", protocol_min, protocol_max", ", 765, 774");

        assertThrows(SQLException.class, () -> rule("reversed", "PROTOCOL", ", protocol_min, protocol_max", ", 774, 765"), "min above max");
        assertThrows(SQLException.class, () -> rule("open", "PROTOCOL", "", ""), "a protocol rule without a range");
        assertThrows(SQLException.class, () -> rule("brand", "BRAND", ", protocol_min, protocol_max", ", 1, 2"), "a range on a brand rule");
    }

    @Test
    void theTemplateActionNeedsATemplateAndOnlyItMayHaveOne() {
        assertThrows(SQLException.class, () -> this.statement.execute(
                "INSERT INTO client_rule (rule_key, display_name, kind, scope_kind, action, created_at, updated_at) VALUES ('a', 'a', 'PROTOCOL', 'GLOBAL', 'TEMPLATE', 1, 1)"),
                "TEMPLATE without a template key");
    }

    @Test
    void aRuleCannotPointAtATemplateThatDoesNotExist() {
        assertThrows(SQLException.class, () -> this.statement.execute(
                "INSERT INTO client_rule (rule_key, display_name, kind, protocol_min, protocol_max, scope_kind, action, template_key, created_at, updated_at) "
                        + "VALUES ('a', 'a', 'PROTOCOL', 1, 2, 'GLOBAL', 'TEMPLATE', 'no-such-template', 1, 1)"));
    }

    @Test
    void aTemplateThatARuleUsesCannotBeDeleted() throws SQLException {
        this.statement.execute("INSERT INTO punishment_templates (identifier, template_key, reason) VALUES (gen_random_uuid(), 'old-client', 'r')");
        this.statement.execute("INSERT INTO client_rule (rule_key, display_name, kind, protocol_min, protocol_max, scope_kind, action, template_key, created_at, updated_at) "
                + "VALUES ('a', 'a', 'PROTOCOL', 1, 2, 'GLOBAL', 'TEMPLATE', 'old-client', 1, 1)");

        SQLException refused = assertThrows(SQLException.class, () -> this.statement.execute("DELETE FROM punishment_templates WHERE template_key = 'old-client'"));
        assertEquals("23001", refused.getSQLState(), "restrict violation");
    }

    @Test
    void deletingARuleDeletesItsPatternsServersAndSections() throws SQLException {
        rule("meteor", "CHANNEL", "", "");
        this.statement.execute("INSERT INTO client_rule_pattern (rule_key, position, pattern) VALUES ('meteor', 0, 'meteor-client:*')");
        this.statement.execute("INSERT INTO client_rule_server (rule_key, position, server_name) VALUES ('meteor', 0, 'lobby-*')");
        this.statement.execute("INSERT INTO client_rule_section (rule_key, position, section_key) VALUES ('meteor', 0, 'minigames')");

        this.statement.execute("DELETE FROM client_rule WHERE rule_key = 'meteor'");

        assertEquals(0, count("client_rule_pattern") + count("client_rule_server") + count("client_rule_section"), "children cascade");
    }

    @Test
    void childRowsNeedTheirRule() {
        assertThrows(SQLException.class, () -> this.statement.execute(
                "INSERT INTO client_rule_pattern (rule_key, position, pattern) VALUES ('ghost', 0, 'x')"));
    }

    @Test
    void aHitCarriesNoTraitColumnAndItsActionIsChecked() throws SQLException {
        rule("meteor", "CHANNEL", "", "");
        this.statement.execute("INSERT INTO client_rule_hit (rule_key, owner_hash, at, action_taken, server_name) VALUES ('meteor', 'o', 1, 'DENY', 'lobby-1')");

        try (ResultSet columns = this.statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 'client_rule_hit' AND table_schema = current_schema() ORDER BY column_name")) {
            java.util.List<String> names = new java.util.ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString(1));
            }
            assertEquals(java.util.List.of("action_taken", "at", "bypassed", "enforced", "identifier", "owner_hash", "rule_key", "server_name"), names,
                    "rule key, owner hash, time, action, flags and server: nothing else");
        }
        assertThrows(SQLException.class, () -> this.statement.execute(
                "INSERT INTO client_rule_hit (rule_key, owner_hash, at, action_taken) VALUES ('meteor', 'o', 1, 'BAN')"));
        assertThrows(SQLException.class, () -> this.statement.execute(
                "INSERT INTO client_rule_hit (rule_key, owner_hash, at, action_taken) VALUES ('ghost', 'o', 1, 'NOTIFY')"), "the rule must exist");
    }

    @Test
    void deletingARuleDeletesItsHits() throws SQLException {
        rule("meteor", "CHANNEL", "", "");
        this.statement.execute("INSERT INTO client_rule_hit (rule_key, owner_hash, at, action_taken) VALUES ('meteor', 'o', 1, 'NOTIFY')");

        this.statement.execute("DELETE FROM client_rule WHERE rule_key = 'meteor'");

        assertEquals(0, count("client_rule_hit"));
    }
}
